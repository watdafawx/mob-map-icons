package dev.watdafawx.mobmapicons.core;

import dev.watdafawx.mobmapicons.ClientConfig;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * Apotheosis invader bosses, recognised on the client alone (no server mod, no packets).
 *
 * <p>Apotheosis marks a boss with {@code apoth.boss} in the entity's persistent data, which never reaches
 * the client, and its {@code from_boss} item flag is not synced either. What does arrive: the mob's custom
 * name (coloured by the boss's rarity) and its equipment with the item's {@code apotheosis:affix_name}
 * component. A boss's affix item is named {@code "<boss name>'s ..."}: the first argument of that name is
 * {@code translatable("%s's", bossName)} (the server's ownership format), whose argument is the boss's own
 * name. A regular affix mob drop never carries its owner's name that way, so the pair (custom name equal to
 * the owner argument on a worn affix item) is the marker. Apotheosis is read through the registries only.
 */
public final class ApothBoss {
    private static final ResourceLocation AFFIX_NAME = ResourceLocation.fromNamespaceAndPath("apotheosis", "affix_name");
    private static final int FALLBACK_COLOR = 0xFFAA00;

    private static boolean resolved;
    private static @Nullable DataComponentType<?> affixName;

    private ApothBoss() {}

    /** Whether {@code entity} is an Apotheosis invader boss. Cheap: no custom name, no work. */
    public static boolean is(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return false;
        Component name = living.getCustomName();
        if (name == null) return false;
        DataComponentType<?> type = affixName();
        if (type == null) return false;
        String boss = name.getString();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = living.getItemBySlot(slot);
            if (stack.isEmpty() || !(stack.get(type) instanceof Component itemName)) continue;
            if (!(itemName.getContents() instanceof TranslatableContents tc) || tc.getArgs().length == 0) continue;
            // The owner argument is translatable("%s's", bossName); the name may be a Component or, after the
            // network round trip, a plain string.
            Object owner = tc.getArgs()[0];
            if (owner instanceof Component oc && oc.getContents() instanceof TranslatableContents own
                    && own.getArgs().length == 1 && boss.equals(text(own.getArgs()[0]))) {
                return true;
            }
        }
        return false;
    }

    /** The boss's display name in its rarity colour, or {@code null} when {@code entity} is no boss. */
    public static @Nullable Component name(Entity entity) {
        return is(entity) ? ((LivingEntity) entity).getCustomName() : null;
    }

    /** Rarity colour (0xRRGGBB) of the boss's name; a gold fallback when the name carries none. */
    public static int color(Entity entity) {
        Component name = entity instanceof LivingEntity living ? living.getCustomName() : null;
        TextColor color = name == null ? null : name.getStyle().getColor();
        return color == null ? FALLBACK_COLOR : color.getValue() & 0xFFFFFF;
    }

    /** Outline colour for the icon variant: the boss's colour, 0 when not a boss or the option is off. */
    static int outlineColor(Entity entity) {
        return ClientConfig.apotheosisBossOutline() && is(entity) ? color(entity) | 0x01000000 : 0;
    }

    private static String text(Object arg) {
        return arg instanceof Component c ? c.getString() : String.valueOf(arg);
    }

    private static @Nullable DataComponentType<?> affixName() {
        if (!resolved) {
            resolved = true;
            if (ModList.get() != null && ModList.get().isLoaded("apotheosis")) {
                affixName = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(AFFIX_NAME).orElse(null);
            }
        }
        return affixName;
    }
}
