package com.example.manualsdk;

import com.example.manualsdk.index.ManualIndex;
import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.model.SearchHit;
import com.example.manualsdk.query.Field;
import com.example.manualsdk.query.ManualQuery;
import com.example.manualsdk.query.QueryException;
import com.example.manualsdk.session.SearchSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryTest {

    @TempDir
    Path dir;

    private ManualIndex indexWithDocs() {
        ManualIndex index = ManualIndex.open(dir);
        index.addAll(List.of(
                new ManualDocument("t1", "Engine repair", "nothing here"),
                new ManualDocument("t2", "Body work", "the engine is loud"),
                new ManualDocument("t3", "Engine and gearbox", "engine service guide"),
                new ManualDocument("t4", "Electrical", "battery check")));
        return index;
    }

    private List<SearchHit> search(ManualIndex index, ManualQuery query) {
        try (SearchSession session = index.openSession(query, 100)) {
            return session.nextPage().hits();
        }
    }

    @Test
    void fieldScopingAndTitleBoost() {
        try (ManualIndex index = indexWithDocs()) {
            var titleHits = search(index, ManualQuery.term(Field.TITLE, "engine"));
            assertEquals(List.of("t1", "t3"), titleHits.stream().map(SearchHit::id).sorted().toList());

            var bodyHits = search(index, ManualQuery.term(Field.BODY, "engine"));
            assertEquals(List.of("t2", "t3"), bodyHits.stream().map(SearchHit::id).sorted().toList());

            var all = search(index, ManualQuery.term(Field.ALL, "engine"));
            assertEquals(3, all.size());
            // t1 matches title only; t2 matches body only -> title boost must win.
            assertTrue(all.stream().filter(h -> h.id().equals("t1")).findFirst().orElseThrow().score()
                    > all.stream().filter(h -> h.id().equals("t2")).findFirst().orElseThrow().score());
        }
    }

    @Test
    void andOrCombinations() {
        try (ManualIndex index = indexWithDocs()) {
            var and = search(index, ManualQuery.and(
                    ManualQuery.term(Field.TITLE, "engine"),
                    ManualQuery.term(Field.BODY, "engine")));
            assertEquals(List.of("t3"), and.stream().map(SearchHit::id).toList());

            var or = search(index, ManualQuery.or(
                    ManualQuery.term(Field.BODY, "battery"),
                    ManualQuery.term(Field.TITLE, "gearbox")));
            assertEquals(List.of("t3", "t4"), or.stream().map(SearchHit::id).sorted().toList());
        }
    }

    @Test
    void phraseAndPrefixQueries() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(
                    new ManualDocument("p1", "Guide", "change the oil filter now"),
                    new ManualDocument("p2", "Guide", "oil and filter separately"),
                    new ManualDocument("p3", "Guide", "transmission and transaxle")));
            var phrase = search(index, ManualQuery.phrase(Field.BODY, "oil filter"));
            assertEquals(List.of("p1"), phrase.stream().map(SearchHit::id).toList());

            var prefix = search(index, ManualQuery.prefix(Field.BODY, "trans"));
            assertEquals(List.of("p3"), prefix.stream().map(SearchHit::id).toList());
        }
    }

    @Test
    void emptyAndIllegalQueriesFailClearly() {
        assertThrows(QueryException.class, () -> ManualQuery.term(Field.ALL, "  "));
        assertThrows(QueryException.class, () -> ManualQuery.phrase(Field.ALL, null));
        assertThrows(QueryException.class, () -> ManualQuery.prefix(Field.ALL, ""));
        assertThrows(QueryException.class, ManualQuery::and);
        assertThrows(QueryException.class, ManualQuery::or);
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(new ManualDocument("a", "t", "b")));
            assertThrows(QueryException.class, () -> index.openSession(null, 10).nextPage());
        }
    }

    @Test
    void stopWordsAreKept() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(new ManualDocument("s1", "the guide", "this is the body")));
            var hits = search(index, ManualQuery.term(Field.BODY, "the"));
            assertEquals(1, hits.size());
        }
    }
}
