package net.kuina.nebulaemirror.block;

import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** A property with arbitrary string values (1.12 enum properties whose values have no 1.21 enum class). */
public final class StringProperty extends Property<String> {
    private final List<String> values;

    public StringProperty(String name, List<String> values) {
        super(name, String.class);
        this.values = List.copyOf(values);
    }

    @Override
    public Collection<String> getPossibleValues() {
        return values;
    }

    @Override
    public String getName(String value) {
        return value;
    }

    @Override
    public Optional<String> getValue(String name) {
        return values.contains(name) ? Optional.of(name) : Optional.empty();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof StringProperty p && super.equals(o) && values.equals(p.values));
    }

    @Override
    public int generateHashCode() {
        return 31 * super.generateHashCode() + values.hashCode();
    }
}
