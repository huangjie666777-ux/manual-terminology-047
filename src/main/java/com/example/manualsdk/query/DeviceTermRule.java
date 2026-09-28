package com.example.manualsdk.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One dictionary rule. {@code source} is directional and is not expanded in
 * reverse; {@code alternatives} are optional replacement phrases.
 */
public record DeviceTermRule(String source, List<String> alternatives) {

    public DeviceTermRule {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(alternatives, "alternatives");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("alternatives must not be empty");
        }
        List<String> copied = new ArrayList<>(alternatives.size());
        for (String alternative : alternatives) {
            if (alternative == null || alternative.isBlank()) {
                throw new IllegalArgumentException("alternatives must not contain blank text");
            }
            copied.add(alternative);
        }
        alternatives = List.copyOf(copied);
    }

    public static DeviceTermRule of(String source, String... alternatives) {
        return new DeviceTermRule(source, List.of(alternatives));
    }
}
