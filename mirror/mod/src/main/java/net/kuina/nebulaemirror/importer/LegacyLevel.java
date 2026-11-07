package net.kuina.nebulaemirror.importer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** The 1.12 world's level.dat: FML id registries (ids differ per world, so they must come from here) and spawn. */
public final class LegacyLevel {
    public final Map<Integer, String> blocks = new HashMap<>();
    public final Map<Integer, String> biomes = new HashMap<>();
    public final Map<String, Integer> biomeIds = new HashMap<>();
    public final BlockPos spawn;
    public final int dataVersion;

    public LegacyLevel(Path levelDat) throws IOException {
        CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
        CompoundTag registries = root.getCompound("FML").getCompound("Registries");
        if (registries.isEmpty()) {
            throw new IOException(levelDat + " has no FML registries (not a 1.12 Forge world?)");
        }
        for (Tag t : registries.getCompound("minecraft:blocks").getList("ids", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            blocks.put(e.getInt("V"), e.getString("K"));
        }
        for (Tag t : registries.getCompound("minecraft:biomes").getList("ids", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            biomes.put(e.getInt("V"), e.getString("K"));
            biomeIds.put(e.getString("K"), e.getInt("V"));
        }
        CompoundTag data = root.getCompound("Data");
        spawn = new BlockPos(data.getInt("SpawnX"), data.getInt("SpawnY"), data.getInt("SpawnZ"));
        dataVersion = data.getInt("DataVersion");
    }
}
