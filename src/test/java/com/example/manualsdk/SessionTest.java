package com.example.manualsdk;

import com.example.manualsdk.index.DocumentOp;
import com.example.manualsdk.index.ManualIndex;
import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.model.SearchHit;
import com.example.manualsdk.query.Field;
import com.example.manualsdk.query.ManualQuery;
import com.example.manualsdk.session.SearchPage;
import com.example.manualsdk.session.SearchSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionTest {

    @TempDir
    Path dir;

    @Test
    void snapshotStableAcrossPagesWhileIndexIsUpdated() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            List<DocumentOp> ops = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ops.add(DocumentOp.add(new ManualDocument("d" + i, "engine", "body " + i)));
            }
            index.applyBatch(ops);

            SearchSession session = index.openSession(ManualQuery.term(Field.TITLE, "engine"), 2);
            SearchPage p0 = session.nextPage();
            assertEquals(2, p0.hits().size());
            assertTrue(p0.hasMore());

            // Updates between pages must not disturb the open session.
            index.applyBatch(List.of(
                    DocumentOp.replace(new ManualDocument("d0", "engine", "drifted body")),
                    DocumentOp.delete("d4"),
                    DocumentOp.add(new ManualDocument("d5", "engine", "new doc"))));

            SearchPage p1 = session.nextPage();
            SearchPage p2 = session.nextPage();
            assertFalse(p2.hasMore());

            List<SearchHit> all = new ArrayList<>();
            all.addAll(p0.hits());
            all.addAll(p1.hits());
            all.addAll(p2.hits());
            assertEquals(List.of("d0", "d1", "d2", "d3", "d4"),
                    all.stream().map(SearchHit::id).sorted().toList());
            // No body drift: the replaced document keeps its original stored text.
            assertEquals("body 0", all.stream().filter(h -> h.id().equals("d0")).findFirst().orElseThrow().body());
            session.close();
            assertThrows(IllegalStateException.class, session::nextPage);

            // A new session sees the latest committed state.
            try (SearchSession fresh = index.openSession(ManualQuery.term(Field.TITLE, "engine"), 10)) {
                var hits = fresh.nextPage().hits();
                assertEquals(List.of("d0", "d1", "d2", "d3", "d5"),
                        hits.stream().map(SearchHit::id).sorted().toList());
                assertEquals("drifted body",
                        hits.stream().filter(h -> h.id().equals("d0")).findFirst().orElseThrow().body());
            }
        }
    }

    @Test
    void orderingIsScoreDescThenIdAsc() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(
                    new ManualDocument("b", "engine engine engine", "x"),
                    new ManualDocument("c", "engine", "x"),
                    new ManualDocument("a", "engine", "x")));
            try (SearchSession session = index.openSession(ManualQuery.term(Field.TITLE, "engine"), 10)) {
                var hits = session.nextPage().hits();
                assertEquals("b", hits.get(0).id());
                assertEquals(List.of("a", "c"), hits.subList(1, 3).stream().map(SearchHit::id).toList());
            }
        }
    }

    @Test
    void invalidPageSizeRejected() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(new ManualDocument("a", "t", "b")));
            assertThrows(IllegalArgumentException.class,
                    () -> index.openSession(ManualQuery.term(Field.ALL, "b"), 0));
        }
    }
}
