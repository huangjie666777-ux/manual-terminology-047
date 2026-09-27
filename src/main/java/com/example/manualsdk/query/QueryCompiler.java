package com.example.manualsdk.query;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a {@link ManualQuery} into a Lucene query. Title clauses carry a
 * boost of {@value #TITLE_BOOST}, body clauses a boost of 1; scoring is the
 * default BM25.
 */
public final class QueryCompiler {

    public static final float TITLE_BOOST = 3.0f;
    public static final String TITLE_FIELD = "title";
    public static final String BODY_FIELD = "body";

    private final Analyzer analyzer;

    public QueryCompiler(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    public Query compile(ManualQuery query) {
        if (query == null) {
            throw new QueryException("query must not be null");
        }
        if (query instanceof ManualQuery.Term term) {
            return scoped(term.field(), field -> new TermQuery(new Term(field, normalize(term.text()))));
        }
        if (query instanceof ManualQuery.Prefix prefix) {
            return scoped(prefix.field(), field -> new PrefixQuery(new Term(field, normalize(prefix.prefix()))));
        }
        if (query instanceof ManualQuery.Phrase phrase) {
            return scoped(phrase.field(), field -> phraseQuery(field, phrase.text()));
        }
        if (query instanceof ManualQuery.And and) {
            return combine(BooleanClause.Occur.MUST, and.children());
        }
        if (query instanceof ManualQuery.Or or) {
            return combine(BooleanClause.Occur.SHOULD, or.children());
        }
        throw new QueryException("unsupported query node: " + query);
    }

    private interface QueryFactory {
        Query create(String luceneField);
    }

    private Query scoped(Field scope, QueryFactory factory) {
        return switch (scope) {
            case TITLE -> new BoostQuery(factory.create(TITLE_FIELD), TITLE_BOOST);
            case BODY -> factory.create(BODY_FIELD);
            case ALL -> new BooleanQuery.Builder()
                    .add(new BoostQuery(factory.create(TITLE_FIELD), TITLE_BOOST), BooleanClause.Occur.SHOULD)
                    .add(factory.create(BODY_FIELD), BooleanClause.Occur.SHOULD)
                    .build();
        };
    }

    private Query combine(BooleanClause.Occur occur, List<ManualQuery> children) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (ManualQuery child : children) {
            builder.add(compile(child), occur);
        }
        return builder.build();
    }

    private Query phraseQuery(String luceneField, String text) {
        List<String> terms = analyze(luceneField, text);
        if (terms.isEmpty()) {
            throw new QueryException("phrase analyzes to no terms: " + text);
        }
        if (terms.size() == 1) {
            return new TermQuery(new Term(luceneField, terms.get(0)));
        }
        return new PhraseQuery(luceneField, terms.toArray(new String[0]));
    }

    /** Analyzes text exactly as indexing does, returning the token stream. */
    public List<String> analyze(String luceneField, String text) {
        List<String> terms = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream(luceneField, text)) {
            CharTermAttribute termAttr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(termAttr.toString());
            }
            stream.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return terms;
    }

    private String normalize(String text) {
        List<String> terms = analyze(TITLE_FIELD, text);
        if (terms.isEmpty()) {
            throw new QueryException("query text analyzes to no terms: " + text);
        }
        if (terms.size() != 1) {
            throw new QueryException("term/prefix query must analyze to exactly one token: " + text);
        }
        return terms.get(0);
    }
}
