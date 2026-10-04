package org.fog.hk261backlog.disruption;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

public final class Validation {

    private Validation() {}

    static List<Integer> edgeKeys(Collection<Integer> keys, String name) {
        Objects.requireNonNull(keys, name);
        if (keys.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        TreeSet<Integer> sorted = new TreeSet<>();
        for (Integer key : keys) {
            Objects.requireNonNull(key, name + " element");
            if (key < 0) throw new IllegalArgumentException(name + " must contain nonnegative keys");
            if (!sorted.add(key)) throw new IllegalArgumentException("Duplicate key in " + name + ": " + key);
        }
        return List.copyOf(sorted);
    }

    public static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(
                name + " must be finite and > 0, got " + value
            );
        }
    }
}
