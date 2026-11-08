package net.kuina.nebulaemirror.writeback;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kuina.nebulaemirror.NebulaeMirror;
import net.kuina.nebulaemirror.block.LegacyBlockEntity;
import net.kuina.nebulaemirror.importer.LegacyLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Mirror block (+ block entity) -> what to write at the origin, in this order:
 * <ol>
 *   <li>air -> air;</li>
 *   <li>{@code nebulaemirror:unknown}: the preserved name, meta and TileEntity, unchanged;</li>
 *   <li>a block with a {@code nebulaemirror:legacy} block entity (C&B, ArchitectureCraft, signs of MoarSigns, ...):
 *       name and meta from its state (so rotating it in the mirror rotates it at the origin), the preserved
 *       1.12 TileEntity;</li>
 *   <li>a vanilla 1.21 state that vanilla 1.12 has ({@link VanillaReverse}) with its TileEntity downgraded;</li>
 *   <li>a mirror block or a FutureMC/Quark override target ({@link ModdedReverse}, the generator's reverse.json);</li>
 *   <li>otherwise an error: 1.12 cannot show this block.</li>
 * </ol>
 */
public final class ReverseTable {
    public record Result(@Nullable LegacyBlock block, @Nullable CompoundTag tileEntity, List<String> warnings,
                         @Nullable String error) {
        public boolean ok() {
            return error == null;
        }

        /** Same 1.12 outcome (what the patch compares). */
        public boolean sameAs(Result o) {
            return ok() && o.ok() && block.equals(o.block) && java.util.Objects.equals(tileEntity, o.tileEntity);
        }
    }

    private final VanillaReverse vanilla;
    private final ModdedReverse modded;
    private final ItemDowngrade items;

    ReverseTable(VanillaReverse vanilla, ModdedReverse modded, ItemDowngrade items) {
        this.vanilla = vanilla;
        this.modded = modded;
        this.items = items;
    }

    /** Built from the origin's level.dat (vanilla numeric ids) and the tables bundled in the jar. */
    public static ReverseTable build(Path legacyLevelDat, Consumer<String> problems) throws IOException {
        long t0 = System.currentTimeMillis();
        LegacyLevel level = new LegacyLevel(legacyLevelDat);
        Map<String, Integer> ids = new HashMap<>();
        level.blocks.forEach((id, name) -> {
            if (name.startsWith("minecraft:")) {
                ids.put(name, id);
            }
        });
        VanillaReverse v = VanillaReverse.build(ids, vanillaMetas(), problems);
        ItemDowngrade items = ItemDowngrade.build();
        ModdedReverse m = ModdedReverse.load();
        NebulaeMirror.LOG.info("Write-back tables: {} vanilla states, {} mapped blocks, {} items in {} ms "
                        + "({} vanilla states 1.21 cannot tell apart; first meta used)", v.size(), m.size(), items.size(),
                System.currentTimeMillis() - t0, v.collisions.size());
        return new ReverseTable(v, m, items);
    }

    static Map<String, Map<Integer, Map<String, String>>> vanillaMetas() {
        try (InputStream in = ReverseTable.class.getResourceAsStream("/nebulaemirror/mirror/vanilla_metas.json")) {
            if (in == null) {
                throw new IllegalStateException("missing /nebulaemirror/mirror/vanilla_metas.json in the mod jar "
                        + "(regenerate with the current generator)");
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parseVanillaMetas(JsonParser.parseReader(r).getAsJsonObject());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Map<Integer, Map<String, String>>> parseVanillaMetas(JsonObject root) {
        Map<String, Map<Integer, Map<String, String>>> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> b : root.getAsJsonObject("blocks").entrySet()) {
            Map<Integer, Map<String, String>> metas = new HashMap<>();
            for (Map.Entry<String, JsonElement> m : b.getValue().getAsJsonObject().entrySet()) {
                Map<String, String> props = new HashMap<>();
                m.getValue().getAsJsonObject().entrySet().forEach(p -> props.put(p.getKey(), p.getValue().getAsString()));
                metas.put(Integer.parseInt(m.getKey()), props);
            }
            out.put(b.getKey(), metas);
        }
        return out;
    }

    /**
     * @param be the block entity's {@code saveWithoutMetadata} data, or null
     */
    public Result reverse(BlockState s, @Nullable CompoundTag be, HolderLookup.Provider registries) {
        List<String> warnings = new ArrayList<>();
        if (s.isAir()) {
            return new Result(LegacyBlock.AIR, null, warnings, null);
        }
        CompoundTag legacy = be != null && be.contains(LegacyBlockEntity.TAG, Tag.TAG_COMPOUND)
                ? be.getCompound(LegacyBlockEntity.TAG) : null;
        if (NebulaeMirror.UNKNOWN != null && s.is(NebulaeMirror.UNKNOWN)) {
            if (legacy == null || legacy.getString("Name").isEmpty()) {
                return new Result(null, null, warnings, "unknown placeholder without its preserved 1.12 data");
            }
            return new Result(new LegacyBlock(legacy.getString("Name"), legacy.getByte("Meta") & 15),
                    tileEntity(legacy), warnings, null);
        }
        if (legacy != null) {
            LegacyBlock b = modded.reverse(s);
            if (b == null) {
                return new Result(null, null, warnings, s + " has no 1.12 meta");
            }
            String kept = legacy.getString("Name");
            if (kept.isEmpty()) {
                warnings.add(s.getBlock().getName().getString() + " has no 1.12 TileEntity data (placed in the mirror?)");
                return new Result(b, null, warnings, null);
            }
            if (!kept.equals(b.name())) {
                warnings.add("preserved TileEntity belongs to " + kept + ", not " + b.name() + "; left out");
                return new Result(b, null, warnings, null);
            }
            return new Result(b, tileEntity(legacy), warnings, null);
        }
        LegacyBlock v = vanilla.reverse(s);
        if (v != null) {
            try {
                CompoundTag te = TileEntityDowngrade.downgrade(s, be, v, items, registries, warnings);
                return new Result(v, te, warnings, null);
            } catch (TileEntityDowngrade.Unsupported e) {
                return new Result(v, null, warnings, e.getMessage());
            }
        }
        LegacyBlock m = modded.reverse(s);
        if (m != null) {
            if (be != null && !be.isEmpty() && hasContent(be)) {
                warnings.add(s.getBlock().getName().getString() + ": block entity data is not written back");
            }
            return new Result(m, null, warnings, null);
        }
        return new Result(null, null, warnings, modded.covers(s) ? s + " is a state 1.12 cannot store"
                : s.getBlock().getName().getString() + " (" + s + ") has no 1.12 equivalent");
    }

    private static boolean hasContent(CompoundTag be) {
        return !be.getList("Items", Tag.TAG_COMPOUND).isEmpty() || be.contains("CustomName");
    }

    @Nullable
    private static CompoundTag tileEntity(CompoundTag legacy) {
        if (!legacy.contains("TileEntity", Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag te = legacy.getCompound("TileEntity").copy();
        te.remove("x");
        te.remove("y");
        te.remove("z");
        return te;
    }

    VanillaReverse vanilla() {
        return vanilla;
    }

    ItemDowngrade items() {
        return items;
    }
}
