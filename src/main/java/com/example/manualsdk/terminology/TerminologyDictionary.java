package com.example.manualsdk.terminology;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, in-memory terminology dictionary. */
public final class TerminologyDictionary {

    private final Map<List<String>, List<List<String>>> rules;

    private TerminologyDictionary(Map<List<String>, List<List<String>>> rules) {
        this.rules = Map.copyOf(rules);
    }

    public static TerminologyDictionary empty() {
        return new TerminologyDictionary(Map.of());
    }

    public static TerminologyDictionary publish(List<TerminologyRule> rules, Analyzer analyzer) {
        if (rules == null) {
            throw new IllegalArgumentException("rules must not be null");
        }
        if (analyzer == null) {
            throw new IllegalArgumentException("analyzer must not be null");
        }

        Map<List<String>, List<List<String>>> compiled = new LinkedHashMap<>();
        for (TerminologyRule rule : rules) {
            if (rule == null) {
                throw new IllegalArgumentException("rule must not be null");
            }
            List<String> source = analyze(analyzer, rule.source());
            if (source.isEmpty()) {
                throw new IllegalArgumentException("source analyzes to no terms: " + rule.source());
            }
            List<List<String>> alternatives = compiled.computeIfAbsent(source, ignored -> new ArrayList<>());
            for (String alternativeText : rule.alternatives()) {
                List<String> alternative = analyze(analyzer, alternativeText);
                if (alternative.isEmpty()) {
                    throw new IllegalArgumentException("alternative analyzes to no terms: " + alternativeText);
                }
                if (!alternatives.contains(alternative)) {
                    alternatives.add(List.copyOf(alternative));
                }
            }
        }
        compiled.replaceAll((source, alternatives) -> List.copyOf(alternatives));
        return new TerminologyDictionary(compiled);
    }

    public List<List<String>> alternativesFor(List<String> source) {
        return rules.getOrDefault(source, List.of());
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    private static List<String> analyze(Analyzer analyzer, String text) {
        List<String> terms = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("terminology", text)) {
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
}
