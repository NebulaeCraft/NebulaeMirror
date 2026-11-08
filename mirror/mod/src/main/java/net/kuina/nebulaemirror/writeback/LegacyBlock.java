package net.kuina.nebulaemirror.writeback;

/** A 1.12 block as the origin stores it: registry name and meta (the numeric id is resolved by the bridge). */
public record LegacyBlock(String name, int meta) {
    public static final LegacyBlock AIR = new LegacyBlock("minecraft:air", 0);

    @Override
    public String toString() {
        return name + ":" + meta;
    }
}
