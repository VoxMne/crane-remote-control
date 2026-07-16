package com.vukotic.crane.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Complete static description of one crane: the "universal" part of the software.
 * Nothing outside a profile may hardcode crane geometry, axis counts, or limits.
 */
public record CraneProfile(String id, String name, List<AxisSpec> axes) {

    public CraneProfile {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("profile id must not be blank");
        }
        if (axes == null || axes.isEmpty()) {
            throw new IllegalArgumentException("profile '%s' must declare at least one axis".formatted(id));
        }
        axes = List.copyOf(axes);
        long uniqueIds = axes.stream().map(AxisSpec::id).distinct().count();
        if (uniqueIds != axes.size()) {
            throw new IllegalArgumentException("profile '%s' has duplicate axis ids".formatted(id));
        }
    }

    public Optional<AxisSpec> axisById(String axisId) {
        return axes.stream().filter(a -> a.id().equals(axisId)).findFirst();
    }

    public List<String> axisIds() {
        return axes.stream().map(AxisSpec::id).toList();
    }
}
