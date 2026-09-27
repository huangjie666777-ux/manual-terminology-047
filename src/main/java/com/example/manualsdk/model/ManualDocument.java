package com.example.manualsdk.model;

import java.util.Objects;

/**
 * A technical manual document. {@code id} must be unique and non-blank;
 * {@code title} and {@code body} may be empty (never null).
 */
public record ManualDocument(String id, String title, String body) {

    public ManualDocument {
        Objects.requireNonNull(id, "id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        title = title == null ? "" : title;
        body = body == null ? "" : body;
    }
}
