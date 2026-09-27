package com.example.manualsdk;

import com.example.manualsdk.index.ManualIndex;
import com.example.manualsdk.model.HitRange;
import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.model.SearchHit;
import com.example.manualsdk.query.Field;
import com.example.manualsdk.query.ManualQuery;
import com.example.manualsdk.session.SearchSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HighlightTest {

    @TempDir
    Path dir;

    private SearchHit singleHit(ManualIndex index, ManualQuery query) {
        try (SearchSession session = index.openSession(query, 10)) {
            var hits = session.nextPage().hits();
            assertEquals(1, hits.size());
            return hits.get(0);
        }
    }

    @Test
    void termRangesUseUtf16HalfOpenOffsets() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            String body = "the ENGINE, engine!";
            index.addAll(List.of(new ManualDocument("a", "", body)));
            SearchHit hit = singleHit(index, ManualQuery.term(Field.BODY, "engine"));
            List<HitRange> ranges = hit.ranges();
            assertEquals(2, ranges.size());
            for (HitRange range : ranges) {
                assertEquals("engine", body.substring(range.start(), range.end()).toLowerCase());
            }
            assertEquals(4, ranges.get(0).start());
            assertEquals(10, ranges.get(0).end());
        }
    }

    @Test
    void phraseMarksOnlyContiguousMatch() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            String body = "oil change now. oil and then change. oil change again";
            index.addAll(List.of(new ManualDocument("a", "", body)));
            SearchHit hit = singleHit(index, ManualQuery.phrase(Field.BODY, "oil change"));
            List<HitRange> ranges = hit.ranges();
            assertEquals(2, ranges.size());
            assertEquals("oil change", body.substring(ranges.get(0).start(), ranges.get(0).end()));
            assertEquals("oil change", body.substring(ranges.get(1).start(), ranges.get(1).end()));
        }
    }

    @Test
    void prefixMarksWholeTokenAndRangesAreSortedAndDeduplicated() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            String body = "trans transmission trans";
            index.addAll(List.of(new ManualDocument("a", "trans", body)));
            ManualQuery query = ManualQuery.or(
                    ManualQuery.prefix(Field.BODY, "trans"),
                    ManualQuery.term(Field.BODY, "trans"));
            SearchHit hit = singleHit(index, query);
            List<HitRange> ranges = hit.ranges();
            assertEquals(3, ranges.size());
            assertEquals("transmission", body.substring(ranges.get(1).start(), ranges.get(1).end()));
            for (int i = 1; i < ranges.size(); i++) {
                assertTrue(ranges.get(i - 1).start() < ranges.get(i).start());
            }
        }
    }

    @Test
    void titleAndBodyRangesAreFieldTagged() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(new ManualDocument("a", "engine title", "engine body")));
            SearchHit hit = singleHit(index, ManualQuery.term(Field.ALL, "engine"));
            assertEquals(2, hit.ranges().size());
            assertEquals(List.of("body", "title"),
                    hit.ranges().stream().map(HitRange::field).sorted().toList());
        }
    }
}
