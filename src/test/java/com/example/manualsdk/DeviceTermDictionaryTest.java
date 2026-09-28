package com.example.manualsdk;

import com.example.manualsdk.index.DocumentOp;
import com.example.manualsdk.index.ManualIndex;
import com.example.manualsdk.model.HitRange;
import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.model.SearchHit;
import com.example.manualsdk.query.DeviceTermRule;
import com.example.manualsdk.query.Field;
import com.example.manualsdk.query.ManualQuery;
import com.example.manualsdk.session.SearchPage;
import com.example.manualsdk.session.SearchSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeviceTermDictionaryTest {

    @TempDir
    Path dir;

    private List<SearchHit> search(ManualIndex index, ManualQuery query) {
        try (SearchSession session = index.openSession(query, 100)) {
            return session.nextPage().hits();
        }
    }

    @Test
    void termExpandsToOriginalAndAlternativesAndHighlightsActualText() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(
                    new ManualDocument("a", "ECU reset", "Replace the ECU."),
                    new ManualDocument("b", "Guide", "Locate the electronic control unit."),
                    new ManualDocument("c", "Guide", "Control unit without electronic.")));

            assertEquals(1, search(index, ManualQuery.term(Field.BODY, "ecu")).size());

            index.replaceDeviceTerms(List.of(
                    DeviceTermRule.of("ecu", "electronic control unit")));

            List<SearchHit> hits = search(index, ManualQuery.term(Field.BODY, "ecu"));
            assertEquals(List.of("a", "b"), hits.stream().map(SearchHit::id).sorted().toList());
            SearchHit b = hits.stream().filter(hit -> hit.id().equals("b")).findFirst().orElseThrow();
            assertEquals(List.of(new HitRange("body", 11, 34)), b.ranges());

            assertEquals(0, search(index, ManualQuery.phrase(Field.BODY, "electronic control unit"))
                    .stream().filter(hit -> hit.id().equals("a")).count());
        }
    }

    @Test
    void expansionIsDirectionalAndNotRecursiveAndPrefixesAreNotExpanded() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(
                    new ManualDocument("ecu", "Guide", "ecu"),
                    new ManualDocument("abc", "Guide", "abc"),
                    new ManualDocument("xyz", "Guide", "xyz"),
                    new ManualDocument("full", "Guide", "electronic control unit")));
            index.replaceDeviceTerms(List.of(
                    DeviceTermRule.of("ecu", "abc", "electronic control unit"),
                    DeviceTermRule.of("abc", "xyz")));

            assertEquals(List.of("abc", "ecu", "full"),
                    search(index, ManualQuery.term(Field.BODY, "ecu")).stream()
                            .map(SearchHit::id).sorted().toList());
            assertEquals(List.of("abc", "xyz"),
                    search(index, ManualQuery.term(Field.BODY, "abc")).stream().map(SearchHit::id).sorted().toList());
            assertEquals(List.of("full"),
                    search(index, ManualQuery.phrase(Field.BODY, "electronic control unit")).stream().map(SearchHit::id).toList());
            assertEquals(List.of("ecu"),
                    search(index, ManualQuery.prefix(Field.BODY, "ecu")).stream().map(SearchHit::id).toList());
        }
    }

    @Test
    void phraseUsesLongestLeftToRightMatchesAndContinuousReplacementPaths() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(
                    new ManualDocument("original", "Guide", "replace brake pad sensor now"),
                    new ManualDocument("both", "Guide", "replace friction material sensor now"),
                    new ManualDocument("invalid", "Guide", "friction material is not a sensor")));
            index.replaceDeviceTerms(List.of(
                    DeviceTermRule.of("pad", "shim"),
                    DeviceTermRule.of("brake pad", "friction material")));

            List<SearchHit> hits = search(index, ManualQuery.phrase(Field.BODY, "brake pad sensor"));
            assertEquals(List.of("both", "original"), hits.stream().map(SearchHit::id).sorted().toList());
            SearchHit both = hits.stream().filter(hit -> hit.id().equals("both")).findFirst().orElseThrow();
            assertEquals("friction material sensor", both.body().substring(
                    both.ranges().get(0).start(), both.ranges().get(0).end()));
        }
    }

    @Test
    void orHighlightsOnlyBranchesThatMatched() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(new ManualDocument("a", "Guide", "battery check")));
            index.replaceDeviceTerms(List.of(DeviceTermRule.of("ecu", "electronic control unit")));
            SearchHit hit = search(index, ManualQuery.or(
                    ManualQuery.term(Field.BODY, "ecu"),
                    ManualQuery.term(Field.BODY, "battery"))).get(0);
            assertEquals(List.of(new HitRange("body", 0, 7)), hit.ranges());
        }
    }

    @Test
    void openSessionPinsDictionaryAcrossPagesUntilClosed() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(
                    new ManualDocument("d1", "Guide", "ecu one"),
                    new ManualDocument("d2", "Guide", "ecu two"),
                    new ManualDocument("d3", "Guide", "electronic control unit three")));

            SearchSession old = index.openSession(ManualQuery.term(Field.BODY, "ecu"), 2);
            SearchPage first = old.nextPage();
            assertEquals(2, first.hits().size());

            index.replaceDeviceTerms(List.of(DeviceTermRule.of("ecu", "electronic control unit")));

            SearchPage second = old.nextPage();
            assertTrue(second.hits().isEmpty());
            assertFalse(second.hasMore());
            old.close();

            try (SearchSession fresh = index.openSession(ManualQuery.term(Field.BODY, "ecu"), 10)) {
                assertEquals(List.of("d1", "d2", "d3"),
                        fresh.nextPage().hits().stream().map(SearchHit::id).sorted().toList());
            }
        }
    }

    @Test
    void invalidPublicationKeepsOldDictionaryAndCallerMutationsDoNotEscape() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(new ManualDocument("a", "Guide",
                    "ecu electronic control unit engine control module")));
            List<DeviceTermRule> rules = new ArrayList<>(List.of(
                    new DeviceTermRule("ecu", new ArrayList<>(List.of("electronic control unit")))));
            index.replaceDeviceTerms(rules);
            rules.add(DeviceTermRule.of("later", "later term"));

            List<String> alternatives = new ArrayList<>(Arrays.asList("engine control module"));
            index.replaceDeviceTerms(List.of(new DeviceTermRule("ecm", alternatives)));
            alternatives.set(0, "zchanged");

            assertEquals(0, search(index, ManualQuery.term(Field.BODY, "later")).size());
            assertEquals(1, search(index, ManualQuery.phrase(Field.BODY, "ecm")).size());
            assertEquals(0, search(index, ManualQuery.term(Field.BODY, "zchanged")).size());

            assertThrows(IllegalArgumentException.class, () -> index.replaceDeviceTerms(List.of(
                    DeviceTermRule.of("valid", "ok"),
                    new DeviceTermRule("bad", List.of("   ")))));
            assertEquals(1, search(index, ManualQuery.phrase(Field.BODY, "ecm")).size());
        }
    }

    @Test
    void emptyDictionaryDisablesExpansionAndDictionaryIsMemoryOnly() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.addAll(List.of(new ManualDocument("a", "Guide", "electronic control unit")));
            index.replaceDeviceTerms(List.of(DeviceTermRule.of("ecu", "electronic control unit")));
            assertEquals(1, search(index, ManualQuery.term(Field.BODY, "ecu")).size());
            index.replaceDeviceTerms(List.of());
            assertEquals(0, search(index, ManualQuery.term(Field.BODY, "ecu")).size());
        }
        try (ManualIndex reopened = ManualIndex.open(dir)) {
            assertEquals(0, search(reopened, ManualQuery.term(Field.BODY, "ecu")).size());
        }
    }

    @Test
    void duplicateAddIdsAndTrailingDeleteInOneBatchDoNotLeaveMultipleDocuments() {
        try (ManualIndex index = ManualIndex.open(dir)) {
            index.applyBatch(List.of(
                    DocumentOp.add(new ManualDocument("same", "engine", "first")),
                    DocumentOp.add(new ManualDocument("same", "engine", "second")),
                    DocumentOp.delete("same")));
            assertEquals(0, search(index, ManualQuery.term(Field.TITLE, "engine")).size());

            index.applyBatch(List.of(
                    DocumentOp.add(new ManualDocument("same", "engine", "first")),
                    DocumentOp.add(new ManualDocument("same", "engine", "second"))));
            assertEquals(1, search(index, ManualQuery.term(Field.TITLE, "engine")).size());
        }
    }
}
