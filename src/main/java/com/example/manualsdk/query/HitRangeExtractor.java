package com.example.manualsdk.query;

import com.example.manualsdk.model.HitRange;
import com.example.manualsdk.terminology.TerminologyDictionary;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Computes exact hit ranges by re-analyzing the stored field text with the
 * indexing analyzer and matching the query tree against the token stream.
 * Offsets are UTF-16 code-unit offsets, start inclusive, end exclusive.
 */
public final class HitRangeExtractor {

    private record Token(String term, int start, int end, int position) {
    }

    private final Analyzer analyzer;
    private final QueryCompiler compiler;

    public HitRangeExtractor(Analyzer analyzer) {
        this.analyzer = analyzer;
        this.compiler = new QueryCompiler(analyzer);
    }

    /** Returns deduplicated, position-sorted hit ranges for one document. */
    public List<HitRange> extract(ManualQuery query, String title, String body) {
        return extract(query, TerminologyDictionary.empty(), title, body);
    }

    /** Returns ranges using the same terminology snapshot as retrieval. */
    public List<HitRange> extract(ManualQuery query, TerminologyDictionary terminology,
                                  String title, String body) {
        TreeSet<HitRange> ranges = new TreeSet<>();
        TerminologyDictionary dictionary = terminology == null ? TerminologyDictionary.empty() : terminology;
        collect(query, dictionary, title, body, ranges);
        return List.copyOf(ranges);
    }

    private boolean collect(ManualQuery query, TerminologyDictionary dictionary,
                            String title, String body, TreeSet<HitRange> out) {
        if (query instanceof ManualQuery.And and) {
            boolean allMatch = true;
            for (ManualQuery child : and.children()) {
                allMatch = collect(child, dictionary, title, body, out) && allMatch;
            }
            return allMatch;
        } else if (query instanceof ManualQuery.Or or) {
            boolean anyMatch = false;
            for (ManualQuery child : or.children()) {
                TreeSet<HitRange> childRanges = new TreeSet<>();
                if (collect(child, dictionary, title, body, childRanges)) {
                    anyMatch = true;
                    out.addAll(childRanges);
                }
            }
            return anyMatch;
        } else if (query instanceof ManualQuery.Term term) {
            return collectLeaf(term.field(), title, body, out,
                    (fields, fieldRanges) -> markPaths(fields,
                            compiler.expandedTermPaths(term.text(), dictionary), fieldRanges));
        } else if (query instanceof ManualQuery.Prefix prefix) {
            return collectLeaf(prefix.field(), title, body, out,
                    (fields, fieldRanges) -> markPrefix(fields,
                            compiler.analyze(QueryCompiler.TITLE_FIELD, prefix.prefix()), fieldRanges));
        } else if (query instanceof ManualQuery.Phrase phrase) {
            return collectLeaf(phrase.field(), title, body, out,
                    (fields, fieldRanges) -> markPaths(fields,
                            compiler.expandedPhrasePaths(phrase.text(), dictionary), fieldRanges));
        }
        throw new QueryException("unsupported query node: " + query);
    }

    private interface PathMatcher {
        boolean matches(List<FieldTokens> fields, TreeSet<HitRange> out);
    }

    private record FieldTokens(String field, List<Token> tokens) {
    }

    private boolean collectLeaf(Field scope, String title, String body, TreeSet<HitRange> out, PathMatcher matcher) {
        List<FieldTokens> fields = new ArrayList<>();
        forEachField(scope, title, body, (field, tokens) -> fields.add(new FieldTokens(field, tokens)));
        return matcher.matches(fields, out);
    }

    private interface FieldConsumer {
        void accept(String field, List<Token> tokens);
    }

    private void forEachField(Field scope, String title, String body, FieldConsumer consumer) {
        if (scope == Field.TITLE || scope == Field.ALL) {
            consumer.accept(QueryCompiler.TITLE_FIELD, tokenize(QueryCompiler.TITLE_FIELD, title));
        }
        if (scope == Field.BODY || scope == Field.ALL) {
            consumer.accept(QueryCompiler.BODY_FIELD, tokenize(QueryCompiler.BODY_FIELD, body));
        }
    }

    private boolean markPrefix(List<FieldTokens> fields, List<String> prefixes, TreeSet<HitRange> out) {
        if (prefixes.size() != 1) {
            throw new QueryException("prefix query must analyze to exactly one token");
        }
        String prefix = prefixes.get(0);
        boolean matched = false;
        for (FieldTokens fieldTokens : fields) {
            for (Token token : fieldTokens.tokens()) {
                if (token.term().startsWith(prefix)) {
                    out.add(new HitRange(fieldTokens.field(), token.start(), token.end()));
                    matched = true;
                }
            }
        }
        return matched;
    }

    private boolean markPaths(List<FieldTokens> fields, List<List<String>> paths, TreeSet<HitRange> out) {
        boolean matchedAny = false;
        for (FieldTokens fieldTokens : fields) {
            List<Token> tokens = fieldTokens.tokens();
            for (List<String> terms : paths) {
                for (int i = 0; i + terms.size() <= tokens.size(); i++) {
                    boolean match = true;
                    int position = tokens.get(i).position();
                    for (int j = 0; j < terms.size(); j++) {
                        Token token = tokens.get(i + j);
                        if (!token.term().equals(terms.get(j)) || token.position() != position + j) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        matchedAny = true;
                        Token first = tokens.get(i);
                        Token last = tokens.get(i + terms.size() - 1);
                        out.add(new HitRange(fieldTokens.field(), first.start(), last.end()));
                    }
                }
            }
        }
        return matchedAny;
    }

    private List<Token> tokenize(String field, String text) {
        List<Token> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream(field, text)) {
            CharTermAttribute termAttr = stream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAttr = stream.addAttribute(OffsetAttribute.class);
            PositionIncrementAttribute posAttr = stream.addAttribute(PositionIncrementAttribute.class);
            stream.reset();
            int position = -1;
            while (stream.incrementToken()) {
                position += posAttr.getPositionIncrement();
                tokens.add(new Token(termAttr.toString(), offsetAttr.startOffset(), offsetAttr.endOffset(), position));
            }
            stream.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return tokens;
    }
}
