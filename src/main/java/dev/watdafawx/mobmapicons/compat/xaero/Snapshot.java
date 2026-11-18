package dev.watdafawx.mobmapicons.compat.xaero;

import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/** Immutable view of which entity types have a Xaero sprite, and the exact bytes of each. */
record Snapshot(Map<ResourceLocation, Entry> entries) {
    static final Snapshot EMPTY = new Snapshot(Map.of());

    /** @param source the core PNG this sprite was made from, kept to detect changes without re-converting */
    record Entry(byte[] source, byte[] sprite) {
        boolean sameSource(byte[] png) {
            return source == png || Arrays.equals(source, png);
        }
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    Entry get(ResourceLocation id) {
        return entries.get(id);
    }

    Collection<ResourceLocation> ids() {
        return entries.keySet();
    }

    /** True when both serve identical files: same types, same source bytes per type. */
    boolean sameContent(Snapshot other) {
        if (entries.size() != other.entries.size()) return false;
        for (var e : entries.entrySet()) {
            var o = other.entries.get(e.getKey());
            if (o == null || !e.getValue().sameSource(o.source())) return false;
        }
        return true;
    }
}
