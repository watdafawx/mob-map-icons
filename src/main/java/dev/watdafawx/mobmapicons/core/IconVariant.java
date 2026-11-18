package dev.watdafawx.mobmapicons.core;

import dev.watdafawx.mobmapicons.MobMapIcons;
import net.minecraft.client.Minecraft;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.TropicalFish;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What makes one entity's icon look different from another of the same type.
 *
 * <ul>
 *   <li>{@code texture}: the renderer's {@code getTextureLocation(entity)}, which covers most
 *       variants (cat, wolf, horse coat, rabbit, fox, frog, axolotl, panda, parrot, llama...).</li>
 *   <li>{@code baby}: {@link LivingEntity#isBaby()}.</li>
 *   <li>{@code scale}: size step relative to the type's standard size, in quarter octaves
 *       ({@code round(4 * log2(ratio))}), 0 when within 5%. Slimes, magma cubes, scaled mobs.</li>
 *   <li>{@code extra}: properties drawn by a render layer rather than the base texture, which
 *       {@code texture} cannot see: wool colour and shearing, villager type and profession, horse
 *       markings, tropical fish pattern and colours, collar colour, charged creeper.</li>
 * </ul>
 * Transient state (pose, crouching, burning, a carried block, sitting, a name) is not part of the key
 * and is reset on the copy that gets drawn, so every icon of one variant looks the same.
 *
 * <p>Equipment is not copied either (the client gets it by a separate packet, not synced data), so
 * icons show mobs without armour or held items, the same as the per-type icon always did.
 */
record IconVariant(@Nullable ResourceLocation texture, boolean baby, int scale, @Nullable String extra) {

    /** Within this ratio of the type's standard size, a mob counts as standard size. */
    private static final float SCALE_TOLERANCE = 0.05F;

    /**
     * The variant of {@code entity}. Any thread; reads only. Never throws: a renderer whose
     * {@code getTextureLocation} fails just leaves {@code texture} null.
     */
    static IconVariant of(Entity entity) {
        ResourceLocation texture = null;
        try {
            texture = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity).getTextureLocation(entity);
        } catch (Throwable ignored) {
            // A renderer that needs more state than a lookup gives; the other fields still separate variants.
        }
        boolean baby = entity instanceof LivingEntity living && living.isBaby();
        return new IconVariant(texture, baby, scaleStep(entity), extra(entity));
    }

    private static int scaleStep(Entity entity) {
        try {
            EntityDimensions standard = entity.getType().getDimensions();
            EntityDimensions actual = entity.getDimensions(Pose.STANDING);
            float ratio = Math.max(actual.width() / Math.max(standard.width(), 1e-3F),
                    actual.height() / Math.max(standard.height(), 1e-3F));
            if (!Float.isFinite(ratio) || ratio <= 0 || Math.abs(ratio - 1.0F) <= SCALE_TOLERANCE) return 0;
            return Math.round(4.0F * (float) (Math.log(ratio) / Math.log(2.0)));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Nullable
    private static String extra(Entity entity) {
        try {
            List<String> parts = new ArrayList<>(3);
            if (entity instanceof Sheep sheep) {
                parts.add(sheep.isSheared() ? "sheared" : sheep.getColor().getSerializedName());
            }
            if (entity instanceof VillagerDataHolder villager) {
                var data = villager.getVillagerData();
                parts.add(data.getType().toString());
                parts.add(data.getProfession().name());
            }
            if (entity instanceof Horse horse) {
                parts.add(horse.getMarkings().name().toLowerCase(Locale.ROOT));
            }
            if (entity instanceof TropicalFish fish) {
                parts.add(fish.getVariant().getSerializedName());
                parts.add(fish.getBaseColor().getSerializedName());
                parts.add(fish.getPatternColor().getSerializedName());
            }
            if (entity instanceof Cat cat && cat.isTame()) parts.add("collar_" + cat.getCollarColor().getSerializedName());
            if (entity instanceof Wolf wolf && wolf.isTame()) parts.add("collar_" + wolf.getCollarColor().getSerializedName());
            if (entity instanceof Creeper creeper && creeper.isPowered()) parts.add("charged");
            return parts.isEmpty() ? null : String.join("_", parts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Short file-name-safe description, e.g. {@code sheep_red}, {@code cat_black_baby}, {@code slime_s8}. */
    String label() {
        List<String> parts = new ArrayList<>(4);
        if (texture != null) {
            String path = texture.getPath();
            int slash = path.lastIndexOf('/');
            String file = path.substring(slash + 1);
            if (file.endsWith(".png")) file = file.substring(0, file.length() - 4);
            parts.add(file);
        }
        if (extra != null) parts.add(extra);
        if (baby) parts.add("baby");
        if (scale != 0) parts.add("s" + scale);
        String label = String.join("_", parts).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return label.isEmpty() ? "default" : label;
    }

    /** Stable 64-bit FNV-1a hash of the key as hex, for resource paths. */
    String hash() {
        String s = Objects.toString(texture) + '|' + baby + '|' + scale + '|' + extra;
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h);
    }

    // ---------------------------------------------------------------------------------------------
    // The throwaway copy that gets drawn (render thread)

    /**
     * A new instance of {@code source}'s type carrying a copy of its synced data (everything the
     * client knows about how it looks), with transient state reset. Not added to any level; the
     * source is only read.
     *
     * @return the copy, or {@code null} when the type cannot be created or the copy fails
     */
    @Nullable
    static Entity copyOf(Entity source) {
        Entity copy = create(source.getType(), source);
        if (copy == null) return null;
        try {
            List<SynchedEntityData.DataValue<?>> values = source.getEntityData().getNonDefaultValues();
            if (values != null) copy.getEntityData().assignValues(values);
        } catch (Throwable t) {
            MobMapIcons.LOG.debug("Could not copy the look of {}: {}", source, t.toString());
            discard(copy);
            return null;
        }
        normalise(copy);
        return copy;
    }

    /** A fresh instance of {@code type} (the type's default look), or {@code null}. */
    @Nullable
    static Entity fresh(EntityType<?> type) {
        Entity entity = create(type, null);
        if (entity != null) normalise(entity);
        return entity;
    }

    @Nullable
    private static Entity create(EntityType<?> type, @Nullable Entity source) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        try {
            if (type == EntityType.PLAYER) {
                // EntityType.PLAYER cannot create(): draw a RemotePlayer wearing the same profile, so it
                // gets the same skin and model from the tab list. Never added to the level.
                var profileOwner = source instanceof net.minecraft.client.player.AbstractClientPlayer p ? p : Minecraft.getInstance().player;
                return profileOwner == null ? null : new net.minecraft.client.player.RemotePlayer(level, profileOwner.getGameProfile());
            }
            return type.create(level);
        } catch (Throwable t) {
            MobMapIcons.LOG.debug("Could not create {} for an icon: {}", type, t.toString());
            return null;
        }
    }

    /** Puts the copy in a standing, unnamed, unburdened state, so one variant always looks the same. */
    private static void normalise(Entity e) {
        try {
            e.setPose(Pose.STANDING);
            e.setShiftKeyDown(false);
            e.setSprinting(false);
            e.setSwimming(false);
            e.setInvisible(false);
            e.setSharedFlagOnFire(false);
            e.setTicksFrozen(0);
            e.setCustomName(null);
            e.setCustomNameVisible(false);
            if (e instanceof LivingEntity living) living.setHealth(living.getMaxHealth());
            if (e instanceof TamableAnimal tamable) tamable.setInSittingPose(false);
            if (e instanceof EnderMan enderman) enderman.setCarriedBlock(null);
        } catch (Throwable t) {
            MobMapIcons.LOG.debug("Could not reset the pose of an icon copy of {}: {}", e.getType(), t.toString());
        }
    }

    static void discard(Entity e) {
        try {
            e.discard();
        } catch (Throwable ignored) {
            // Never in a level; a modded remove() hook failing here changes nothing.
        }
    }
}
