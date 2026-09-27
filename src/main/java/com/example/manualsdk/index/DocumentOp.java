package com.example.manualsdk.index;

import com.example.manualsdk.model.ManualDocument;

import java.util.Objects;

/**
 * One write operation inside a batch. Duplicate ids inside a batch are
 * applied in submission order, so the last operation for an id wins.
 */
public sealed interface DocumentOp {

    String id();

    record Add(ManualDocument document) implements DocumentOp {
        public Add {
            Objects.requireNonNull(document, "document");
        }

        @Override
        public String id() {
            return document.id();
        }
    }

    record Replace(ManualDocument document) implements DocumentOp {
        public Replace {
            Objects.requireNonNull(document, "document");
        }

        @Override
        public String id() {
            return document.id();
        }
    }

    record Delete(String id) implements DocumentOp {
        public Delete {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
        }

        @Override
        public String id() {
            return id;
        }
    }

    static DocumentOp add(ManualDocument document) {
        return new Add(document);
    }

    static DocumentOp replace(ManualDocument document) {
        return new Replace(document);
    }

    static DocumentOp delete(String id) {
        return new Delete(id);
    }
}
