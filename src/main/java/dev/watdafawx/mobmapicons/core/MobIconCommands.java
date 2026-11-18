package dev.watdafawx.mobmapicons.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.watdafawx.mobmapicons.MobMapIcons;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code /mobmapicons dump} writes every cached icon to {@code <gamedir>/mob_map_icons_dump/};
 * {@code /mobmapicons render <entity_type>} re-renders one type and writes it there too.
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
                .then(Commands.literal("dump").executes(MobIconCommands::dump))
                .then(Commands.literal("render")
                        .then(Commands.argument("entity_type", ResourceLocationArgument.id())
                                .suggests((ctx, builder) ->
                                        SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.keySet(), builder))
                                .executes(MobIconCommands::render))));
    }

    private static int dump(CommandContext<CommandSourceStack> ctx) {
        Path dir = dumpDir();
        try {
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
}
