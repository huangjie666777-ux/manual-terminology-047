package com.example.manualsdk.terminology;

import com.example.manualsdk.index.DocumentOp;
import com.example.manualsdk.index.ManualIndex;
import com.example.manualsdk.model.HitRange;
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

class TerminologyTest {

    @TempDir
    Path dir;

    private List<SearchHit> search(ManualIndex index, ManualQuery query) {
        try (SearchSession session = index.openSession(query, 100)) {
            return session.nextPage().hits();
        }
    }

    @Test
    void rulesValidateMergeAndAreDefensivelyCopied() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            List<String> alternatives = new ArrayList<>(List.of("power supply unit", "power supply unit", "PSU"));
            List<TerminologyRule> rules = new ArrayList<>(List.of(
                    new TerminologyRule("psu", alternatives),
                    TerminologyRule.of("psu", "power supply")));

            index.replaceTerminology(rules);
            rules.clear();
            alternatives.clear();

            index.addAll(List.of(new ManualDocument("d1", "PSU guide", "replace the power supply unit now")));
            assertEquals(List.of("d1"), search(index, ManualQuery.term(Field.BODY, "psu")).stream().map(SearchHit::id).toList());
            assertEquals(List.of("d1"), search(index, ManualQuery.term(Field.TITLE, "psu")).stream().map(SearchHit::id).toList());

            assertThrows(IllegalArgumentException.class,
                    () -> index.replaceTerminology(List.of(TerminologyRule.of("", "power supply"))));
            assertThrows(IllegalArgumentException.class,
                    () -> index.replaceTerminology(List.of(TerminologyRule.of("bad", "   "))));
            assertEquals(List.of("d1"), search(index, ManualQuery.term(Field.BODY, "psu")).stream().map(SearchHit::id).toList());
        }
    }

    @Test
    void termsAndPhrasesUseAlternativesButPrefixesDoNot() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.replaceTerminology(List.of(TerminologyRule.of("ecm", "engine control module")));
            index.addAll(List.of(
                    new ManualDocument("a", "ECM", "original abbreviation"),
                    new ManualDocument("b", "module", "engine control module guide"),
                    new ManualDocument("c", "module", "engine control prefix")));

            assertEquals(List.of("a", "b"), search(index, ManualQuery.term(Field.ALL, "ecm")).stream().map(SearchHit::id).sorted().toList());
            assertEquals(List.of("b"), search(index, ManualQuery.phrase(Field.ALL, "ecm guide")).stream().map(SearchHit::id).toList());
            assertEquals(List.of(), search(index, ManualQuery.prefix(Field.BODY, "ecm")).stream().map(SearchHit::id).toList());
        }
    }

    @Test
    void expansionIsDirectionalAndNonRecursive() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.replaceTerminology(List.of(TerminologyRule.of("ecu", "electronic control unit")));
            index.addAll(List.of(
                    new ManualDocument("ecu", "ecu text", "x"),
                    new ManualDocument("full", "electronic control unit text", "x"),
                    new ManualDocument("nested", "the electronic control units followed by ecu text", "x")));

            assertEquals(List.of("ecu", "full", "nested"),
                    search(index, ManualQuery.phrase(Field.TITLE, "ecu text")).stream().map(SearchHit::id).sorted().toList());
            assertEquals(List.of(),
                    search(index, ManualQuery.phrase(Field.TITLE, "electronic control unit guide"))
                            .stream().map(SearchHit::id).toList());
            assertEquals(List.of("full"),
                    search(index, ManualQuery.phrase(Field.TITLE, "electronic control unit"))
                            .stream().map(SearchHit::id).toList());
            assertEquals(List.of("full"),
                    search(index, ManualQuery.phrase(Field.TITLE, "electronic control unit text")).stream().map(SearchHit::id).toList());
        }
    }

    @Test
    void longestRuleAndMultipleReplacementsCombineWithoutCrossPaths() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.replaceTerminology(List.of(
                    TerminologyRule.of("oil filter", "lubricant filter"),
                    TerminologyRule.of("filter", "strainer")));
            index.addAll(List.of(
                    new ManualDocument("valid1", "x", "replace lubricant filter now"),
                    new ManualDocument("valid2", "x", "replace oil filter now"),
                    new ManualDocument("invalid", "x", "lubricant strainer now")));

            var hits = search(index, ManualQuery.phrase(Field.BODY, "replace oil filter now"));
            assertEquals(List.of("valid1", "valid2"), hits.stream().map(SearchHit::id).sorted().toList());
            assertFalse(hits.stream().map(SearchHit::id).anyMatch("invalid"::equals));
        }
    }

    @Test
    void failedBooleanBranchesAreNotHighlighted() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.replaceTerminology(List.of(TerminologyRule.of("ecu", "electronic control unit")));
            index.addAll(List.of(new ManualDocument("d", "ecu", "electronic control unit battery")));

            ManualQuery falseOr = ManualQuery.or(
                    ManualQuery.phrase(Field.BODY, "ecu missing"),
                    ManualQuery.term(Field.BODY, "battery"));
            SearchHit hit = search(index, falseOr).get(0);
            assertEquals(List.of(new HitRange("body", 24, 31)), hit.ranges());

            ManualQuery trueAnd = ManualQuery.and(
                    ManualQuery.term(Field.TITLE, "ecu"),
                    ManualQuery.term(Field.BODY, "battery"));
            SearchHit andHit = search(index, trueAnd).get(0);
            assertEquals(List.of("body", "title"), andHit.ranges().stream().map(HitRange::field).sorted().toList());
        }
    }

    @Test
    void alternativePhraseHighlightUsesOriginalUtf16Offsets() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.replaceTerminology(List.of(TerminologyRule.of("ecu", "engine control unit")));
            String body = "prefix " + "\uD83D\uDE00 engine control unit suffix";
            index.addAll(List.of(new ManualDocument("d", "x", body)));

            SearchHit hit = search(index, ManualQuery.term(Field.BODY, "ecu")).get(0);
            assertEquals(List.of(new HitRange("body", 10, 29)), hit.ranges());
            assertEquals("engine control unit", body.substring(10, 29));
        }
    }

    @Test
    void sessionKeepsDictionaryAcrossPagesAndHighlights() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.replaceTerminology(List.of(TerminologyRule.of("ecu", "electronic control unit")));
            List<DocumentOp> docs = new ArrayList<>();
            docs.add(DocumentOp.add(new ManualDocument("a0", "ECU", "old expansion text")));
            for (int i = 1; i < 4; i++) {
                docs.add(DocumentOp.add(new ManualDocument("a" + i, "electronic control unit", "body " + i)));
            }
            index.applyBatch(docs);

            SearchSession old = index.openSession(ManualQuery.term(Field.TITLE, "ecu"), 2);
            SearchPage first = old.nextPage();
            assertEquals(2, first.hits().size());

            index.replaceTerminology(List.of(TerminologyRule.of("other", "different term")));
            SearchPage second = old.nextPage();
            assertEquals(List.of("a2", "a3"), second.hits().stream().map(SearchHit::id).sorted().toList());
            assertFalse(second.hits().isEmpty());
            assertEquals("electronic control unit", second.hits().get(0).title());

            try (SearchSession fresh = index.openSession(ManualQuery.term(Field.TITLE, "ecu"), 10)) {
                assertEquals(List.of("a0"), fresh.nextPage().hits().stream().map(SearchHit::id).toList());
            }
            old.close();

            index.clearTerminology();
            try (SearchSession disabled = index.openSession(ManualQuery.term(Field.TITLE, "ecu"), 10)) {
                assertEquals(List.of("a0"), disabled.nextPage().hits().stream().map(SearchHit::id).toList());
            }
        }
    }

}
