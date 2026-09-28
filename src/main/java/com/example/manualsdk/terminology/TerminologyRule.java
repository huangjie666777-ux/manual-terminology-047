package com.example.manualsdk.terminology;

import java.util.List;
import java.util.Objects;

/**
 * One directional terminology rule. {@code source} is not expanded again
 * when it appears in an alternative phrase.
 */
public record TerminologyRule(String source, List<String> alternatives) {

    public TerminologyRule {
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        Objects.requireNonNull(alternatives, "alternatives");
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("alternatives must not be empty");
        }
        alternatives = List.copyOf(alternatives);
        for (String alternative : alternatives) {
            Objects.requireNonNull(alternative, "alternative");
            if (alternative.isBlank()) {
                throw new IllegalArgumentException("alternative must not be blank");
            }
        }
    }

    public static TerminologyRule of(String source, String... alternatives) {
        return new TerminologyRule(source, List.of(alternatives));
    }
}
