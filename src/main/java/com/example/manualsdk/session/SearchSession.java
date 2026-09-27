package com.example.manualsdk.session;

import com.example.manualsdk.index.ManualIndex;
import com.example.manualsdk.model.HitRange;
import com.example.manualsdk.model.SearchHit;
import com.example.manualsdk.query.ManualQuery;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopFieldDocs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A search session pinned to the index snapshot taken at creation time.
 * Pages are read one at a time via {@link #nextPage()} without preloading
 * all hits; updates committed after creation never leak into the session.
 * Sessions must be closed, and are closed automatically when the owning
 * index closes.
 */
public final class SearchSession implements AutoCloseable {

    private static final Sort SORT = new Sort(SortField.FIELD_SCORE,
            new SortField("idSort", SortField.Type.STRING));

    private final ManualIndex index;
    private final ManualQuery query;
    private final int pageSize;
    private final IndexSearcher searcher;

    private ScoreDoc after;
    private int pageNumber;
    private boolean exhausted;
    private volatile boolean closed;

    public SearchSession(ManualIndex index, ManualQuery query, int pageSize) throws IOException {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        this.index = index;
        this.query = query;
        this.pageSize = pageSize;
        this.searcher = index.searcherManager().acquire();
    }

    /** Reads the next page from the fixed snapshot. */
    public synchronized SearchPage nextPage() {
        ensureUsable();
        if (exhausted) {
            return new SearchPage(List.of(), pageNumber, false);
        }
        try {
            Query luceneQuery = index.queryCompiler().compile(query);
            TopFieldDocs top = searcher.searchAfter(after, luceneQuery, pageSize, SORT, true);
            List<SearchHit> hits = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc scoreDoc : top.scoreDocs) {
                hits.add(toHit(scoreDoc));
            }
            if (top.scoreDocs.length > 0) {
                after = top.scoreDocs[top.scoreDocs.length - 1];
            }
            exhausted = top.scoreDocs.length < pageSize;
            return new SearchPage(hits, pageNumber++, !exhausted);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SearchHit toHit(ScoreDoc scoreDoc) throws IOException {
        Document doc = searcher.storedFields().document(scoreDoc.doc);
        String id = doc.get("id");
        String title = doc.get("title");
        String body = doc.get("body");
        List<HitRange> ranges = index.hitExtractor().extract(query, title, body);
        return new SearchHit(id, scoreDoc.score, title, body, ranges);
    }

    private void ensureUsable() {
        if (closed) {
            throw new IllegalStateException("session is closed");
        }
        if (index.isClosed()) {
            close();
            throw new IllegalStateException("owning index is closed");
        }
    }

    /** Releases the pinned snapshot. Further use is rejected. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        index.releaseSession(this);
        try {
            index.searcherManager().release(searcher);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
