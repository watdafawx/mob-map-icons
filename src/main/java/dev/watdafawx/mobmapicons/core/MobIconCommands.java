package dev.watdafawx.mobmapicons.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.blaze3d.platform.NativeImage;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.IconMode;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Client commands, all writing to {@code <gamedir>/mob_map_icons_dump/}:
 * <ul>
 *   <li>{@code /mobmapicons dump}: every cached icon (type defaults, variants, heading buckets), plus
 *       {@code contact_sheet.png} with all of them and {@code contact_sheet.txt} naming each cell;</li>
 *   <li>{@code /mobmapicons render <entity_type>}: re-renders one type's default icon;</li>
 *   <li>{@code /mobmapicons headings}: the mob under the crosshair (or the nearest one) at all
 *       {@link MobIconApi#YAW_BUCKETS} headings, as one contact sheet.</li>
 * </ul>
 */
final class MobIconCommands {

    static final String DUMP_DIR = "mob_map_icons_dump";

    private MobIconCommands() {}

    static Path dumpDir() {
        return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve(DUMP_DIR);
    }

    static void register(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("mobmapicons")
                .then(Commands.literal("reload").executes(ctx -> { MobIconCache.invalidateAll(); return 1; }))
                .then(Commands.literal("style").then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((ctx,b) -> SharedSuggestionProvider.suggest(java.util.Arrays.stream(dev.watdafawx.mobmapicons.api.IconStyle.values()).map(v -> v.name().toLowerCase(java.util.Locale.ROOT)),b))
                        .executes(ctx -> {
                            try {
                                var style=dev.watdafawx.mobmapicons.api.IconStyle.valueOf(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx,"name").toUpperCase(java.util.Locale.ROOT));
                                ClientConfig.ICON_STYLE.set(style); ClientConfig.SPEC.save(); MobIconCache.checkConfig();
                                ctx.getSource().sendSuccess(() -> Component.literal("Icon style: " + style), false); return 1;
                            } catch (IllegalArgumentException e) { ctx.getSource().sendFailure(Component.literal("Unknown icon style")); return 0; }
                        })))
                .then(Commands.literal("options").executes(ctx -> { QuickOptions.requestOpen(); return 1; }))
                .then(Commands.literal("dump").executes(MobIconCommands::dump))
                .then(Commands.literal("headings").executes(MobIconCommands::headings))
                .then(Commands.literal("render")
                        .then(Commands.argument("entity_type", ResourceLocationArgument.id())
                                .suggests((ctx, builder) ->
                                        SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.keySet(), builder))
                                .executes(MobIconCommands::render))));
    }

    private static int dump(CommandContext<CommandSourceStack> ctx) {
        Path dir = dumpDir();
        try {
            MobIconCache.runInFrame(() -> { try { StyleContactSheet.write(dir, java.util.List.of()); } catch (IOException e) { MobMapIcons.LOG.error("Style dump failed", e); } });
            int n = MobIconCache.dumpAll(dir);
            ctx.getSource().sendSuccess(() -> Component.literal("Wrote " + n + " mob icon(s) to " + dir), false);
            return n;
        } catch (IOException e) {
            MobMapIcons.LOG.error("Mob icon dump failed", e);
            ctx.getSource().sendFailure(Component.literal("Dump failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int render(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "entity_type");
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        CommandSourceStack source = ctx.getSource();
        if (type == null) {
            source.sendFailure(Component.literal("Unknown entity type " + id));
            return 0;
        }
        MobIconCache.rerender(type, entry -> {
            if (!entry.ready() || entry.png() == null) {
                source.sendFailure(Component.literal("No icon for " + id + " (cannot be created or draws nothing; see log)"));
                return;
            }
            try {
                Path file = MobIconCache.writePng(dumpDir(), id, "", entry.png());
                source.sendSuccess(() -> Component.literal("Rendered " + id + " -> " + file), false);
            } catch (IOException e) {
                source.sendFailure(Component.literal("Rendered " + id + " but could not write it: " + e.getMessage()));
            }
        });
        return 1;
    }

    private static int headings(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        CommandSourceStack source = ctx.getSource();
        Entity target = mc.crosshairPickEntity;
        if (!(target instanceof LivingEntity) || target instanceof Player) {
            target = null;
            if (mc.level != null && mc.player != null) {
                double best = 32.0 * 32.0;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (!(e instanceof LivingEntity) || e instanceof Player) continue;
                    double d = e.distanceToSqr(mc.player);
                    if (d < best) {
                        best = d;
                        target = e;
                    }
                }
            }
        }
        if (target == null) {
            source.sendFailure(Component.literal("No mob in sight or within 32 blocks"));
            return 0;
        }
        Entity mob = target;
        MobIconCache.runInFrame(() -> {
            try {
                Path file = writeHeadings(dumpDir(), mob, ClientConfig.iconMode(), ClientConfig.iconSize() * 2,
                        ClientConfig.rotateWithHeading(), "");
                source.sendSuccess(() -> Component.literal("Wrote " + MobIconApi.YAW_BUCKETS + " headings of "
                        + mob.getType().getDescription().getString() + " -> " + file), false);
            } catch (IOException e) {
                source.sendFailure(Component.literal("Headings failed: " + e.getMessage()));
            }
        });
        return 1;
    }

    /**
     * Renders {@code mob}'s look at every heading bucket, left to right from bucket 0, into
     * {@code dir/headings_<type>__<variant><suffix>.png}. Render thread.
     */
    static Path writeHeadings(Path dir, Entity mob, IconMode mode, int outSize, boolean sharedFrame, String suffix)
            throws IOException {
        List<NativeImage> images = new ArrayList<>(MobIconApi.YAW_BUCKETS);
        for (int b = 0; b < MobIconApi.YAW_BUCKETS; b++) {
            NativeImage image = MobIconCache.renderOnce(mob.getType(), mob, mode, outSize, b, sharedFrame);
            images.add(image != null ? image : new NativeImage(outSize, outSize, true));
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        Path file = dir.resolve("headings_" + id.getPath() + "__" + IconVariant.of(mob).label() + suffix + ".png");
        ContactSheet.write(file, images, MobIconApi.YAW_BUCKETS, Math.max(1, 64 / outSize));
        return file;
    }
}
