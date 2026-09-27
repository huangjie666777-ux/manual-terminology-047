package com.example.manualsdk.index;

import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.query.HitRangeExtractor;
import com.example.manualsdk.query.ManualQuery;
import com.example.manualsdk.query.QueryCompiler;
import com.example.manualsdk.session.SearchSession;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point of the SDK. Owns the Lucene index stored in a directory and
 * supports atomic batches of add / replace / delete operations. Writes and
 * queries may run in parallel inside one process; readers always see the
 * last committed batch.
 */
public final class ManualIndex implements AutoCloseable {

    static final String ID_FIELD = "id";
    static final String ID_SORT_FIELD = "idSort";

    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    private final QueryCompiler queryCompiler;
    private final HitRangeExtractor hitExtractor;
    private final Set<SearchSession> openSessions = ConcurrentHashMap.newKeySet();
    private final Object writeLock = new Object();
    private volatile boolean closed;

    private ManualIndex(Directory directory) throws IOException {
        this.directory = directory;
        this.analyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(directory, config);
        writer.commit();
        this.searcherManager = new SearcherManager(writer, null);
        this.queryCompiler = new QueryCompiler(analyzer);
        this.hitExtractor = new HitRangeExtractor(analyzer);
    }

    /** Opens (creating if needed) the index stored in {@code dir}. */
    public static ManualIndex open(Path dir) {
        try {
            return new ManualIndex(NIOFSDirectory.open(dir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Validates and applies a whole batch atomically. If validation fails an
     * {@link IllegalArgumentException} is thrown and the searchable content
     * is left unchanged; on success the entire batch becomes visible at once
     * and survives a close/reopen cycle.
     */
    public void applyBatch(List<DocumentOp> operations) {
        ensureOpen();
        validate(operations);
        synchronized (writeLock) {
            ensureOpen();
            try {
                for (DocumentOp op : operations) {
                    if (op instanceof DocumentOp.Add add) {
                        writer.addDocument(toLuceneDocument(add.document()));
                    } else if (op instanceof DocumentOp.Replace replace) {
                        writer.updateDocument(idTerm(replace.id()), toLuceneDocument(replace.document()));
                    } else if (op instanceof DocumentOp.Delete delete) {
                        writer.deleteDocuments(idTerm(delete.id()));
                    }
                }
                writer.commit();
                searcherManager.maybeRefreshBlocking();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public void addAll(List<ManualDocument> documents) {
        applyBatch(documents.stream().<DocumentOp>map(DocumentOp.Add::new).toList());
    }

    public void replaceAll(List<ManualDocument> documents) {
        applyBatch(documents.stream().<DocumentOp>map(DocumentOp.Replace::new).toList());
    }

    public void deleteAll(List<String> ids) {
        applyBatch(ids.stream().<DocumentOp>map(DocumentOp.Delete::new).toList());
    }

    private void validate(List<DocumentOp> operations) {
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("batch must contain at least one operation");
        }
        for (DocumentOp op : operations) {
            if (op == null) {
                throw new IllegalArgumentException("batch contains a null operation");
            }
            // Record constructors already enforce non-blank ids and non-null documents.
        }
    }

    /**
     * Opens a search session pinned to the current index snapshot. Updates
     * committed after this call are invisible to the session; new sessions
     * see the latest committed state.
     */
    public SearchSession openSession(ManualQuery query, int pageSize) {
        ensureOpen();
        try {
            SearchSession session = new SearchSession(this, query, pageSize);
            openSessions.add(session);
            return session;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void releaseSession(SearchSession session) {
        openSessions.remove(session);
    }

    public SearcherManager searcherManager() {
        return searcherManager;
    }

    public QueryCompiler queryCompiler() {
        return queryCompiler;
    }

    public HitRangeExtractor hitExtractor() {
        return hitExtractor;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("index is closed");
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private static Term idTerm(String id) {
        return new Term(ID_FIELD, id);
    }

    private static Document toLuceneDocument(ManualDocument doc) {
        Document document = new Document();
        document.add(new org.apache.lucene.document.StringField(ID_FIELD, doc.id(), Field.Store.YES));
        document.add(new SortedDocValuesField(ID_SORT_FIELD, new BytesRef(doc.id())));
        document.add(new TextField(QueryCompiler.TITLE_FIELD, doc.title(), Field.Store.YES));
        document.add(new TextField(QueryCompiler.BODY_FIELD, doc.body(), Field.Store.YES));
        return document;
    }

    /** Closes all open sessions and releases every index resource. */
    @Override
    public void close() {
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            closed = true;
            for (SearchSession session : openSessions) {
                session.close();
            }
            openSessions.clear();
            try {
                searcherManager.close();
                writer.close();
                analyzer.close();
                directory.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
