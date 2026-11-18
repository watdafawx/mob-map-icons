package dev.watdafawx.mobmapicons.api;

/**
 * Which map mod an icon is drawn for. {@link #GLOBAL} is the top-level config; the others can override
 * it per option in their own config section (see {@code ClientConfig}). Icons are cached per effective
 * style, mode and size, so different map mods can show different looks at the same time.
 */
public enum MapMod {
    GLOBAL("global"),
    FTB("ftb"),
    XAERO("xaero"),
    JOURNEYMAP("journeymap");

    private final String id;

    MapMod(String id) {
        this.id = id;
    }

    /** Config section name and language key stem. */
    public String id() {
        return id;
    }
}
