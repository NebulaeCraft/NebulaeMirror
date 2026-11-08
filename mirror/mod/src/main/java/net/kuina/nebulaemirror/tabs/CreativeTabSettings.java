package net.kuina.nebulaemirror.tabs;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the mirror's creative tabs read when they are built (client only; without a loaded config every tab is
 * shown with its default icon). Applied from {@link CreativeTabConfig} by the client.
 */
public final class CreativeTabSettings {
    private static volatile Set<String> hidden = Set.of();
    private static volatile Map<String, ItemStack> icons = Map.of();

    private CreativeTabSettings() {
    }

    public static boolean shown(String tab) {
        return !hidden.contains(tab);
    }

    /** The configured icon, or null for the tab's default. */
    public static ItemStack icon(String tab) {
        ItemStack s = icons.get(tab);
        return s == null ? null : s.copy();
    }

    /** Resolves the icons against the item registry; returns one message per entry that was ignored. */
    public static List<String> apply(CreativeTabConfig config) {
        Set<String> h = new HashSet<>();
        Map<String, ItemStack> ic = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, CreativeTabConfig.Tab> e : config.creativeTabs.entrySet()) {
            String tab = e.getKey();
            CreativeTabConfig.Tab t = e.getValue();
            if (!t.show) {
                h.add(tab);
            }
            try {
                CreativeTabConfig.IconSpec spec = CreativeTabConfig.IconSpec.parse(t.icon);
                if (spec != null) {
                    ic.put(tab, stack(spec));
                }
            } catch (IllegalArgumentException ex) {
                problems.add(tab + ": icon \"" + t.icon + "\": " + ex.getMessage());
            }
        }
        hidden = Set.copyOf(h);
        icons = Map.copyOf(ic);
        return problems;
    }

    private static ItemStack stack(CreativeTabConfig.IconSpec spec) {
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(spec.id())).orElse(Items.AIR);
        if (item == Items.AIR) {
            throw new IllegalArgumentException("no such item");
        }
        ItemStack stack = new ItemStack(item);
        if (!spec.state().isEmpty()) {
            if (!(item instanceof BlockItem bi)) {
                throw new IllegalArgumentException("[...] needs a block item");
            }
            StateDefinition<?, ?> def = bi.getBlock().getStateDefinition();
            for (Map.Entry<String, String> p : spec.state().entrySet()) {
                Property<?> prop = def.getProperty(p.getKey());
                if (prop == null) {
                    throw new IllegalArgumentException("no property " + p.getKey() + " (has " + names(def) + ")");
                }
                if (prop.getValue(p.getValue()).isEmpty()) {
                    throw new IllegalArgumentException(p.getKey() + " has no value " + p.getValue()
                            + " (values " + prop.getPossibleValues().stream().map(v -> valueName(prop, v)).toList() + ")");
                }
            }
            stack.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(spec.state()));
        }
        return stack;
    }

    private static List<String> names(StateDefinition<?, ?> def) {
        return def.getProperties().stream().map(Property::getName).toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(Property prop, Object v) {
        return prop.getName((Comparable) v);
    }
}
