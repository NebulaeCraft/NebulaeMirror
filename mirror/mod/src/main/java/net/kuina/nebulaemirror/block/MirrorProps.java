package net.kuina.nebulaemirror.block;

import net.kuina.nebulaemirror.data.MirrorData;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Builds 1.21 properties from generator definitions and maps states to generator variant keys. */
public final class MirrorProps {
    /**
     * Block constructors build the state definition before subclass fields exist, so the extra properties of the
     * block being constructed are handed over through this thread-local (set by the registrar around `new`).
     */
    public static final ThreadLocal<List<Property<?>>> PENDING = ThreadLocal.withInitial(List::of);

    private MirrorProps() {
    }

    public static Property<?> create(MirrorData.PropDef p) {
        return switch (p.kind()) {
            case "bool" -> BooleanProperty.create(p.name());
            case "int" -> IntegerProperty.create(p.name(), p.min(), p.max());
            case "direction" -> {
                List<Direction> dirs = new ArrayList<>();
                for (String v : p.values()) {
                    dirs.add(Direction.byName(v));
                }
                yield DirectionProperty.create(p.name(), dirs);
            }
            case "axis" -> EnumProperty.create(p.name(), Direction.Axis.class);
            default -> new StringProperty(p.name(), p.values());
        };
    }

    public static List<Property<?>> createAll(List<MirrorData.PropDef> defs) {
        List<Property<?>> out = new ArrayList<>();
        for (MirrorData.PropDef d : defs) {
            out.add(create(d));
        }
        return out;
    }

    /** Variant key of a state, restricted to the generator's keyed property names. */
    public static String key(BlockState state, Set<String> keyed) {
        Map<String, String> props = new HashMap<>();
        for (Property<?> p : state.getProperties()) {
            if (keyed.contains(p.getName())) {
                props.put(p.getName(), valueName(state, p));
            }
        }
        return MirrorData.variantKey(props);
    }

    public static <T extends Comparable<T>> String valueName(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    /** state with p set to the named value, or unchanged if the value is not allowed. */
    public static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> p, String value) {
        Optional<T> v = p.getValue(value);
        return v.isPresent() ? state.setValue(p, v.get()) : state;
    }

    public static BlockState apply(BlockState state, Map<String, String> values) {
        for (Property<?> p : state.getProperties()) {
            String v = values.get(p.getName());
            if (v != null) {
                state = with(state, p, v);
            }
        }
        return state;
    }
}
