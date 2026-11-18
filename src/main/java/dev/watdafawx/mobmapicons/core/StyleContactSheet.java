package dev.watdafawx.mobmapicons.core;

import com.mojang.blaze3d.platform.NativeImage;
import dev.watdafawx.mobmapicons.api.IconMode;
import dev.watdafawx.mobmapicons.api.IconStyle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

final class StyleContactSheet {
    static void write(Path dir, List<Entity> variants) throws IOException {
        List<NativeImage> grid=new ArrayList<>();
        List<String> names=new ArrayList<>();
        List<Entity> mobs=new ArrayList<>();
        for(EntityType<?> type:List.of(EntityType.ZOMBIE,EntityType.SKELETON,EntityType.CREEPER,EntityType.ENDERMAN,EntityType.PIG,EntityType.COW,EntityType.SHEEP,EntityType.CHICKEN,EntityType.WOLF,EntityType.VILLAGER,EntityType.IRON_GOLEM,EntityType.BAT,EntityType.SLIME,EntityType.ENDER_DRAGON,EntityType.AXOLOTL,EntityType.SQUID,EntityType.GHAST,EntityType.WARDEN,EntityType.ALLAY,EntityType.SPIDER)) {
            Entity e=MobIconCache.makeCopy(type,null); if(e!=null)mobs.add(e);
        }
        if(variants.isEmpty()) {
            Entity baby=MobIconCache.makeCopy(EntityType.COW,null);
            if(baby instanceof net.minecraft.world.entity.AgeableMob ageable) {ageable.setBaby(true);mobs.add(baby);}
            for(var color:List.of(net.minecraft.world.item.DyeColor.RED,net.minecraft.world.item.DyeColor.BLUE)) {
                Entity sheep=MobIconCache.makeCopy(EntityType.SHEEP,null);
                if(sheep instanceof net.minecraft.world.entity.animal.Sheep wool) {wool.setColor(color);mobs.add(sheep);}
            }
        }
        mobs.addAll(variants);
        try {
            for(Entity e:mobs) {
                names.add(e.getType().toString() + " " + IconVariant.of(e).label());
                try(NativeImage full=MobIconRenderer.render(e,IconMode.FULL_BODY,64,0,null);
                    NativeImage head=MobIconRenderer.render(e,IconMode.HEAD,64,0,null);
                    NativeImage top=MobIconRenderer.render(e,IconMode.FULL_BODY,64,0,null,true)) {
                    for(IconStyle style:IconStyle.values()) grid.add(full==null?new NativeImage(64,64,true):IconStyles.apply(style,style==IconStyle.TOP_DOWN&&top!=null?top:full,head,e,-1));
                }
            }
            Files.createDirectories(dir);
            Files.write(dir.resolve("styles.txt"),names);
            Files.writeString(dir.resolve("styles-columns.txt"),Arrays.toString(IconStyle.values()));
            // Each style also gets its own vertical strip, alongside the comparison matrix.
            for(int c=0;c<IconStyle.values().length;c++) {
                List<NativeImage> column=new ArrayList<>();
                for(int r=0;r<mobs.size();r++) {NativeImage copy=new NativeImage(64,64,true);copy.copyFrom(grid.get(r*IconStyle.values().length+c));column.add(copy);}
                ContactSheet.write(dir.resolve("style_"+IconStyle.values()[c].name().toLowerCase(Locale.ROOT)+".png"),column,1,1);
            }
            ContactSheet.write(dir.resolve("styles.png"),grid,IconStyle.values().length,1);
        } finally { for(int i=0;i<mobs.size()-variants.size();i++)IconVariant.discard(mobs.get(i)); }
    }
}
