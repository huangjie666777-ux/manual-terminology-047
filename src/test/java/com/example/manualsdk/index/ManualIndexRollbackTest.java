package com.example.manualsdk.index;

import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.model.SearchHit;
import com.example.manualsdk.query.Field;
import com.example.manualsdk.query.ManualQuery;
import com.example.manualsdk.session.SearchSession;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManualIndexRollbackTest {

    @TempDir
    Path dir;

    @Test
    void ioFailureRollsBackAndDoesNotPoisonLaterCommit() throws IOException {
        FailingDirectory failingDirectory = new FailingDirectory(NIOFSDirectory.open(dir));
        try (ManualIndex index = ManualIndex.open(failingDirectory)) {
            index.addAll(List.of(new ManualDocument("existing", "engine", "old")));
            failingDirectory.failNextSync = true;

            assertThrows(RuntimeException.class, () -> index.applyBatch(List.of(
                    DocumentOp.add(new ManualDocument("left", "engine", "must roll back")),
                    DocumentOp.add(new ManualDocument("new", "battery", "new")))));
            try (SearchSession afterFailure = index.openSession(ManualQuery.term(Field.BODY, "battery"), 10)) {
                assertTrue(afterFailure.nextPage().hits().isEmpty());
            }

            index.applyBatch(List.of(DocumentOp.add(new ManualDocument("later", "engine", "later"))));
            try (SearchSession session = index.openSession(ManualQuery.term(Field.ALL, "engine"), 10)) {
                List<SearchHit> hits = session.nextPage().hits();
                assertEquals(List.of("existing", "later"), hits.stream().map(SearchHit::id).sorted().toList());
            }
        }
    }

    private static final class FailingDirectory extends FilterDirectory {
        private boolean failNextSync;

        private FailingDirectory(Directory in) {
            super(in);
        }

        @Override
        public void sync(Collection<String> names) throws IOException {
            if (failNextSync) {
                failNextSync = false;
                throw new IOException("simulated commit failure");
            }
            super.sync(names);
        }

    }
}
