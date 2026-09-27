package com.example.manualsdk;

import com.example.manualsdk.index.DocumentOp;
import com.example.manualsdk.index.ManualIndex;
import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.query.Field;
import com.example.manualsdk.query.ManualQuery;
import com.example.manualsdk.session.SearchSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManualIndexTest {

    @TempDir
    Path dir;

    @Test
    void batchAddReplaceDeleteAndDuplicateIdsApplyInOrder() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.applyBatch(List.of(
                    DocumentOp.add(new ManualDocument("a", "engine", "first")),
                    DocumentOp.add(new ManualDocument("b", "engine", "second"))));
            index.applyBatch(List.of(
                    DocumentOp.replace(new ManualDocument("a", "gearbox", "replaced")),
                    DocumentOp.replace(new ManualDocument("a", "engine", "final version")),
                    DocumentOp.delete("b")));

            try (SearchSession session = index.openSession(ManualQuery.term(Field.ALL, "engine"), 10)) {
                var hits = session.nextPage().hits();
                assertEquals(1, hits.size());
                assertEquals("a", hits.get(0).id());
                assertEquals("final version", hits.get(0).body());
            }
        }
    }

    @Test
    void invalidBatchLeavesSearchableContentUnchanged() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(new ManualDocument("a", "engine", "body")));
            assertThrows(IllegalArgumentException.class, () -> index.applyBatch(List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> index.applyBatch(java.util.Arrays.asList(DocumentOp.delete("a"), null)));
            assertThrows(IllegalArgumentException.class, () -> DocumentOp.delete(" "));
            assertThrows(IllegalArgumentException.class, () -> new ManualDocument("", "t", "b"));

            try (SearchSession session = index.openSession(ManualQuery.term(Field.ALL, "engine"), 10)) {
                assertEquals(1, session.nextPage().hits().size());
            }
        }
    }

    @Test
    void committedDataSurvivesReopen() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(new ManualDocument("a", "engine", "persistent")));
        }
        try (ManualIndex reopened = ManualIndex.open(dir);
             SearchSession session = reopened.openSession(ManualQuery.term(Field.ALL, "persistent"), 10)) {
            assertEquals(1, session.nextPage().hits().size());
        }
    }

    @Test
    void closingIndexClosesSessions() {
        ManualIndex index = ManualIndex.open(dir);
        index.addAll(List.of(new ManualDocument("a", "engine", "body")));
        SearchSession session = index.openSession(ManualQuery.term(Field.ALL, "engine"), 10);
        index.close();
        assertThrows(IllegalStateException.class, session::nextPage);
        index.close(); // second close is a no-op
    }
}
