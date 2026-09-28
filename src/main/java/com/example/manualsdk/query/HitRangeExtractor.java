package com.example.manualsdk.query;

import com.example.manualsdk.model.HitRange;
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
        TreeSet<HitRange> ranges = new TreeSet<>();
        collect(query, title, body, ranges);
        return List.copyOf(ranges);
    }

    private boolean collect(ManualQuery query, String title, String body, TreeSet<HitRange> out) {
        if (query instanceof ManualQuery.And and) {
            TreeSet<HitRange> local = new TreeSet<>();
            for (ManualQuery child : and.children()) {
                if (!collect(child, title, body, local)) {
                    return false;
                }
            }
            out.addAll(local);
            return true;
        }
        if (query instanceof ManualQuery.Or or) {
            TreeSet<HitRange> local = new TreeSet<>();
            boolean matched = false;
            for (ManualQuery child : or.children()) {
                if (collect(child, title, body, local)) {
                    matched = true;
                }
            }
            if (matched) {
                out.addAll(local);
            }
            return matched;
        }
        TreeSet<HitRange> local = new TreeSet<>();
        boolean matched;
        if (query instanceof ManualQuery.Term term) {
            forEachField(term.field(), title, body,
                    (field, tokens) -> markTerm(tokens, field, compiler.analyze(field, term.text()), local));
            matched = !local.isEmpty();
        } else if (query instanceof ManualQuery.Prefix prefix) {
            forEachField(prefix.field(), title, body,
                    (field, tokens) -> markPrefix(tokens, field, compiler.analyze(field, prefix.prefix()), local));
            matched = !local.isEmpty();
        } else if (query instanceof ManualQuery.Phrase phrase) {
            forEachField(phrase.field(), title, body,
                    (field, tokens) -> markPhrase(tokens, field, compiler.analyze(field, phrase.text()), local));
            matched = !local.isEmpty();
        } else {
            throw new QueryException("unsupported query node: " + query);
        }
        if (matched) {
            out.addAll(local);
        }
        return matched;
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

    private void markTerm(List<Token> tokens, String field, List<String> terms, TreeSet<HitRange> out) {
        if (terms.size() != 1) {
            return;
        }
        String wanted = terms.get(0);
        for (Token token : tokens) {
            if (token.term().equals(wanted)) {
                out.add(new HitRange(field, token.start(), token.end()));
            }
        }
    }

    private void markPrefix(List<Token> tokens, String field, List<String> prefixes, TreeSet<HitRange> out) {
        if (prefixes.size() != 1) {
            return;
        }
        String prefix = prefixes.get(0);
        for (Token token : tokens) {
            if (token.term().startsWith(prefix)) {
                out.add(new HitRange(field, token.start(), token.end()));
            }
        }
    }

    private void markPhrase(List<Token> tokens, String field, List<String> terms, TreeSet<HitRange> out) {
        if (terms.isEmpty()) {
            return;
        }
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
                Token first = tokens.get(i);
                Token last = tokens.get(i + terms.size() - 1);
                out.add(new HitRange(field, first.start(), last.end()));
            }
        }
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
