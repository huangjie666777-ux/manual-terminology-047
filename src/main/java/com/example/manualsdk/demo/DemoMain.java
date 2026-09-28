package com.example.manualsdk.demo;

import com.example.manualsdk.index.DocumentOp;
import com.example.manualsdk.index.ManualIndex;
import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.model.SearchHit;
import com.example.manualsdk.query.DeviceTermRule;
import com.example.manualsdk.query.Field;
import com.example.manualsdk.query.ManualQuery;
import com.example.manualsdk.session.SearchPage;
import com.example.manualsdk.session.SearchSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * End-to-end demo: batch writes, paged reading across updates (snapshot
 * isolation), phrase/prefix queries with hit ranges, and persistence.
 */
public final class DemoMain {

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("manual-sdk-demo");
        ManualQuery query = ManualQuery.or(
                ManualQuery.term(Field.TITLE, "engine"),
                ManualQuery.phrase(Field.BODY, "oil filter"),
                ManualQuery.prefix(Field.ALL, "trans"));

        try (ManualIndex index = ManualIndex.open(dir)) {
            index.applyBatch(List.of(
                    DocumentOp.add(new ManualDocument("M-001", "Engine maintenance", "Replace the oil filter every 10000 km.")),
                    DocumentOp.add(new ManualDocument("M-002", "Transmission service", "The transmission fluid must be checked.")),
                    DocumentOp.add(new ManualDocument("M-003", "Engine cooling", "Inspect the engine coolant level weekly.")),
                    DocumentOp.add(new ManualDocument("M-004", "Brake system", "Brake pads and oil filter are unrelated parts.")),
                    DocumentOp.add(new ManualDocument("M-005", "Electrical", "Check the battery terminals."))));

            System.out.println("== Paged session over a fixed snapshot (pageSize=2) ==");
            SearchSession session = index.openSession(query, 2);
            print(session.nextPage());

            // Concurrent updates while paging: invisible to the open session.
            index.applyBatch(List.of(
                    DocumentOp.replace(new ManualDocument("M-002", "Transmission overhaul", "Full transmission rebuild guide.")),
                    DocumentOp.delete("M-004"),
                    DocumentOp.add(new ManualDocument("M-006", "Engine tuning", "Advanced engine tuning and oil filter notes."))));

            print(session.nextPage());
            print(session.nextPage());
            session.close();

            System.out.println("== New session sees the latest committed state ==");
            try (SearchSession fresh = index.openSession(query, 10)) {
                print(fresh.nextPage());
            }

            System.out.println("== Device-term dictionary: old session is pinned, new sessions use the replacement ==");
            ManualQuery abbreviation = ManualQuery.term(Field.BODY, "ecu");
            index.applyBatch(List.of(
                    DocumentOp.add(new ManualDocument("T-001", "Controller", "The ECU is under the cover.")),
                    DocumentOp.add(new ManualDocument("T-002", "Location", "Find the electronic control unit behind the panel."))));
            try (SearchSession before = index.openSession(abbreviation, 1)) {
                System.out.println("before dictionary, page 1:");
                print(before.nextPage());

                index.replaceDeviceTerms(List.of(
                        DeviceTermRule.of("ecu", "electronic control unit")));

                System.out.println("after dictionary, old session page 2 stays on its snapshot:");
                print(before.nextPage());
            }
            try (SearchSession after = index.openSession(abbreviation, 10)) {
                System.out.println("new session after dictionary:");
                print(after.nextPage());
            }
        }

        System.out.println("== Reopened index keeps committed results ==");
        try (ManualIndex reopened = ManualIndex.open(dir);
             SearchSession session = reopened.openSession(ManualQuery.term(Field.TITLE, "engine"), 10)) {
            print(session.nextPage());
        }
    }

    private static void print(SearchPage page) {
        System.out.println("-- page " + page.pageNumber() + " (hasMore=" + page.hasMore() + ")");
        for (SearchHit hit : page.hits()) {
            System.out.printf("   %s score=%.4f title=%s ranges=%s%n",
                    hit.id(), hit.score(), hit.title(), hit.ranges());
        }
    }
}
