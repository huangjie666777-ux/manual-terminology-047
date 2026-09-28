package com.example.manualsdk.query;

import com.example.manualsdk.terminology.TerminologyDictionary;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        return compile(query, TerminologyDictionary.empty());
    }

    public Query compile(ManualQuery query, TerminologyDictionary terminology) {
        if (query == null) {
            throw new QueryException("query must not be null");
        }
        TerminologyDictionary dictionary = terminology == null ? TerminologyDictionary.empty() : terminology;
        if (query instanceof ManualQuery.Term term) {
            List<String> source = singleTerm(term.text());
            return scoped(term.field(), field -> pathsQuery(field, expand(source, dictionary)));
        }
        if (query instanceof ManualQuery.Prefix prefix) {
            return scoped(prefix.field(), field -> new PrefixQuery(new Term(field, normalize(prefix.prefix()))));
        }
        if (query instanceof ManualQuery.Phrase phrase) {
            List<String> source = phraseTerms(phrase.text());
            return scoped(phrase.field(), field -> pathsQuery(field, expand(source, dictionary)));
        }
        if (query instanceof ManualQuery.And and) {
            return combine(BooleanClause.Occur.MUST, and.children(), dictionary);
        }
        if (query instanceof ManualQuery.Or or) {
            return combine(BooleanClause.Occur.SHOULD, or.children(), dictionary);
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

    private Query combine(BooleanClause.Occur occur, List<ManualQuery> children, TerminologyDictionary dictionary) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (ManualQuery child : children) {
            builder.add(compile(child, dictionary), occur);
        }
        return builder.build();
    }

    private Query pathsQuery(String luceneField, List<List<String>> paths) {
        Set<Query> queries = new LinkedHashSet<>();
        for (List<String> path : paths) {
            queries.add(leafQuery(luceneField, path));
        }
        if (queries.size() == 1) {
            return queries.iterator().next();
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (Query query : queries) {
            builder.add(query, BooleanClause.Occur.SHOULD);
        }
        return builder.build();
    }

    private Query leafQuery(String luceneField, List<String> terms) {
        if (terms.size() == 1) {
            return new TermQuery(new Term(luceneField, terms.get(0)));
        }
        return new PhraseQuery(luceneField, terms.toArray(new String[0]));
    }

    private List<List<String>> expand(List<String> source, TerminologyDictionary dictionary) {
        List<List<String>> paths = new ArrayList<>();
        expand(source, dictionary, 0, new ArrayList<>(), paths);
        return List.copyOf(paths);
    }

    private void expand(List<String> source, TerminologyDictionary dictionary, int index,
                        List<String> current, List<List<String>> paths) {
        if (index == source.size()) {
            paths.add(List.copyOf(current));
            return;
        }
        int matchLength = longestRule(source, dictionary, index);
        if (matchLength == 0) {
            current.add(source.get(index));
            expand(source, dictionary, index + 1, current, paths);
            current.remove(current.size() - 1);
            return;
        }

        List<String> original = source.subList(index, index + matchLength);
        addPath(source, dictionary, index, matchLength, current, paths, original);
        for (List<String> alternative : dictionary.alternativesFor(original)) {
            addPath(source, dictionary, index, matchLength, current, paths, alternative);
        }
    }

    private void addPath(List<String> source, TerminologyDictionary dictionary, int index, int matchLength,
                         List<String> current, List<List<String>> paths, List<String> replacement) {
        int size = current.size();
        current.addAll(replacement);
        expand(source, dictionary, index + matchLength, current, paths);
        current.subList(size, current.size()).clear();
    }

    private int longestRule(List<String> source, TerminologyDictionary dictionary, int index) {
        for (int length = source.size() - index; length > 0; length--) {
            if (!dictionary.alternativesFor(source.subList(index, index + length)).isEmpty()) {
                return length;
            }
        }
        return 0;
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

    private List<String> singleTerm(String text) {
        List<String> terms = analyze(TITLE_FIELD, text);
        if (terms.isEmpty()) {
            throw new QueryException("query text analyzes to no terms: " + text);
        }
        if (terms.size() != 1) {
            throw new QueryException("term/prefix query must analyze to exactly one token: " + text);
        }
        return terms;
    }

    private List<String> phraseTerms(String text) {
        List<String> terms = analyze(TITLE_FIELD, text);
        if (terms.isEmpty()) {
            throw new QueryException("phrase analyzes to no terms: " + text);
        }
        return terms;
    }

    public List<List<String>> expandedPhrasePaths(String text, TerminologyDictionary dictionary) {
        return expand(phraseTerms(text), dictionary == null ? TerminologyDictionary.empty() : dictionary);
    }

    public List<List<String>> expandedTermPaths(String text, TerminologyDictionary dictionary) {
        return expand(singleTerm(text), dictionary == null ? TerminologyDictionary.empty() : dictionary);
    }
}
