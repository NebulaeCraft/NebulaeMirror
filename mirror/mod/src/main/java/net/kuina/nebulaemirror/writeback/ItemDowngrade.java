package net.kuina.nebulaemirror.writeback;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.ItemIdFix;
import net.minecraft.util.datafix.fixes.ItemStackTheFlatteningFix;
import net.minecraft.util.datafix.fixes.References;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 1.21 item stack NBT -> 1.12 item NBT ({id, Count, Damage, tag}), for container contents.
 * The id table is built like {@link VanillaReverse}: every 1.12 item name ({@code ItemIdFix}) and damage 0..15
 * goes through the flattening ({@code ItemStackTheFlatteningFix}) and the later ITEM_NAME renames; inverted,
 * the lowest (name, damage) wins. Kept: count, damage (tools), custom name, lore, enchantments.
 * Everything else in the components is dropped and reported.
 */
public final class ItemDowngrade {
    record Legacy(String name, int damage) {
    }

    /** 1.12 numeric enchantment ids. */
    static final Map<String, Integer> ENCHANTMENTS = Map.ofEntries(
            Map.entry("protection", 0), Map.entry("fire_protection", 1), Map.entry("feather_falling", 2),
            Map.entry("blast_protection", 3), Map.entry("projectile_protection", 4), Map.entry("respiration", 5),
            Map.entry("aqua_affinity", 6), Map.entry("thorns", 7), Map.entry("depth_strider", 8),
            Map.entry("frost_walker", 9), Map.entry("binding_curse", 10), Map.entry("sharpness", 16),
            Map.entry("smite", 17), Map.entry("bane_of_arthropods", 18), Map.entry("knockback", 19),
            Map.entry("fire_aspect", 20), Map.entry("looting", 21), Map.entry("sweeping", 22),
            Map.entry("efficiency", 32), Map.entry("silk_touch", 33), Map.entry("unbreaking", 34),
            Map.entry("fortune", 35), Map.entry("power", 48), Map.entry("punch", 49), Map.entry("flame", 50),
            Map.entry("infinity", 51), Map.entry("luck_of_the_sea", 61), Map.entry("lure", 62),
            Map.entry("mending", 70), Map.entry("vanishing_curse", 71));
    private static final Set<String> KEPT = Set.of("minecraft:damage", "minecraft:custom_name", "minecraft:lore",
            "minecraft:enchantments", "minecraft:stored_enchantments");

    private final Map<String, Legacy> byId = new HashMap<>();

    private ItemDowngrade() {
    }

    public static ItemDowngrade build() {
        ItemDowngrade d = new ItemDowngrade();
        int to = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        for (int id = 1; id < 2400; id++) { // 1.12 item ids end at 2267 (records)
            String name = ItemIdFix.getItem(id);
            if (name == null || name.equals("minecraft:air")) {
                continue;
            }
            for (int damage = 0; damage < 16; damage++) {
                String flat = ItemStackTheFlatteningFix.updateItem(name, damage);
                if (flat == null) {
                    if (damage > 0) {
                        continue; // not a variant item: the damage is durability or meaningless
                    }
                    flat = name;
                }
                Dynamic<Tag> out = DataFixers.getDataFixer().update(References.ITEM_NAME,
                        new Dynamic<>(NbtOps.INSTANCE, StringTag.valueOf(flat)), VanillaReverse.FLATTENED_VERSION, to);
                String now = out.getValue().getAsString();
                d.byId.putIfAbsent(now, new Legacy(name, damage));
            }
        }
        return d;
    }

    Legacy legacy(String id) {
        return byId.get(id);
    }

    public int size() {
        return byId.size();
    }

    /** Null if the item has no 1.12 form (reported in {@code warnings}). */
    public CompoundTag downgrade(CompoundTag item, HolderLookup.Provider registries, List<String> warnings) {
        String id = item.getString("id");
        Legacy l = byId.get(id);
        if (l == null) {
            warnings.add("物品 " + id + " 在 1.12 中没有对应形式，已略去");
            return null;
        }
        CompoundTag out = new CompoundTag();
        out.putString("id", l.name());
        out.putByte("Count", (byte) Math.max(1, Math.min(127, item.contains("count") ? item.getInt("count") : 1)));
        CompoundTag comps = item.getCompound("components");
        int damage = comps.contains("minecraft:damage") ? comps.getInt("minecraft:damage") : l.damage();
        out.putShort("Damage", (short) damage);
        CompoundTag tag = new CompoundTag();
        CompoundTag display = new CompoundTag();
        if (comps.contains("minecraft:custom_name", Tag.TAG_STRING)) {
            display.putString("Name", plain(comps.getString("minecraft:custom_name"), registries));
        }
        if (comps.contains("minecraft:lore", Tag.TAG_LIST)) {
            ListTag lore = new ListTag();
            for (Tag t : comps.getList("minecraft:lore", Tag.TAG_STRING)) {
                lore.add(StringTag.valueOf(plain(t.getAsString(), registries)));
            }
            display.put("Lore", lore);
        }
        if (!display.isEmpty()) {
            tag.put("display", display);
        }
        enchantments(comps.getCompound("minecraft:enchantments"), "ench", tag, warnings);
        enchantments(comps.getCompound("minecraft:stored_enchantments"), "StoredEnchantments", tag, warnings);
        Set<String> dropped = new TreeSet<>(comps.getAllKeys());
        dropped.removeAll(KEPT);
        dropped.removeIf(k -> k.startsWith("!"));
        if (!dropped.isEmpty()) {
            warnings.add("物品 " + id + "：丢弃了 " + dropped);
        }
        if (!tag.isEmpty()) {
            out.put("tag", tag);
        }
        return out;
    }

    private static void enchantments(CompoundTag comp, String key, CompoundTag tag, List<String> warnings) {
        CompoundTag levels = comp.getCompound("levels");
        if (levels.isEmpty()) {
            return;
        }
        ListTag list = new ListTag();
        for (String e : levels.getAllKeys()) {
            String path = e.startsWith("minecraft:") ? e.substring(10) : e;
            Integer n = ENCHANTMENTS.get(path);
            if (n == null) {
                warnings.add("附魔 " + e + " 在 1.12 中没有对应 ID，已略去");
                continue;
            }
            CompoundTag one = new CompoundTag();
            one.putShort("id", (short) (int) n);
            one.putShort("lvl", (short) levels.getInt(e));
            list.add(one);
        }
        if (!list.isEmpty()) {
            tag.put(key, list);
        }
    }

    /** 1.21 stores text components as JSON; 1.12 item names and lore are plain strings. */
    static String plain(String json, HolderLookup.Provider registries) {
        try {
            Component c = Component.Serializer.fromJson(json, registries);
            return c == null ? json : c.getString();
        } catch (RuntimeException e) {
            return json;
        }
    }

    /** "Items" of a 1.21 container -> 1.12 "Items" (same slot numbers). */
    public ListTag downgradeItems(ListTag items, HolderLookup.Provider registries, List<String> warnings) {
        ListTag out = new ListTag();
        for (Tag t : items) {
            CompoundTag item = (CompoundTag) t;
            CompoundTag old = downgrade(item, registries, warnings);
            if (old != null) {
                old.putByte("Slot", item.getByte("Slot"));
                out.add(old);
            }
        }
        return out;
    }
}
