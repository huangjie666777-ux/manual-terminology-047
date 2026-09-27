package com.example.manualsdk.query;

import java.util.List;
import java.util.Objects;

/**
 * Immutable query tree. Supports term, phrase and trailing-prefix clauses
 * scoped to title, body or both, combined with AND / OR.
 */
public sealed interface ManualQuery {

    record Term(Field field, String text) implements ManualQuery {
        public Term {
            Objects.requireNonNull(field, "field");
            if (text == null || text.isBlank()) {
                throw new QueryException("term text must not be empty");
            }
        }
    }

    /** Consecutive phrase; the text is analyzed with the indexing analyzer. */
    record Phrase(Field field, String text) implements ManualQuery {
        public Phrase {
            Objects.requireNonNull(field, "field");
            if (text == null || text.isBlank()) {
                throw new QueryException("phrase text must not be empty");
            }
        }
    }

    /** Trailing-prefix clause: matches whole tokens starting with {@code prefix}. */
    record Prefix(Field field, String prefix) implements ManualQuery {
        public Prefix {
            Objects.requireNonNull(field, "field");
            if (prefix == null || prefix.isBlank()) {
                throw new QueryException("prefix must not be empty");
            }
        }
    }

    record And(List<ManualQuery> children) implements ManualQuery {
        public And {
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new QueryException("AND query requires at least one clause");
            }
        }
    }

    record Or(List<ManualQuery> children) implements ManualQuery {
        public Or {
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new QueryException("OR query requires at least one clause");
            }
        }
    }

    static ManualQuery term(Field field, String text) {
        return new Term(field, text);
    }

    static ManualQuery phrase(Field field, String text) {
        return new Phrase(field, text);
    }

    static ManualQuery prefix(Field field, String prefix) {
        return new Prefix(field, prefix);
    }

    static ManualQuery and(ManualQuery... children) {
        return new And(List.of(children));
    }

    static ManualQuery or(ManualQuery... children) {
        return new Or(List.of(children));
    }
}
