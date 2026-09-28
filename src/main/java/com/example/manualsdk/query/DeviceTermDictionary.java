package com.example.manualsdk.query;

import org.apache.lucene.analysis.Analyzer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, in-memory directional device-term dictionary. */
public final class DeviceTermDictionary {

    private static final DeviceTermDictionary EMPTY = new DeviceTermDictionary(Map.of());

    private final Map<List<String>, List<List<String>>> rules;

    private DeviceTermDictionary(Map<List<String>, List<List<String>>> rules) {
        this.rules = Map.copyOf(rules);
    }

    public static DeviceTermDictionary empty() {
        return EMPTY;
    }

    public static DeviceTermDictionary compile(Analyzer analyzer, Collection<DeviceTermRule> input) {
        QueryCompiler compiler = new QueryCompiler(analyzer);
        Map<List<String>, Set<List<String>>> merged = new LinkedHashMap<>();
        if (input == null) {
            throw new IllegalArgumentException("rules must not be null");
        }
        for (DeviceTermRule rule : input) {
            if (rule == null) {
                throw new IllegalArgumentException("rule must not be null");
            }
            List<String> source = compiler.analyze(QueryCompiler.TITLE_FIELD, rule.source());
            if (source.isEmpty()) {
                throw new IllegalArgumentException("source analyzes to no terms: " + rule.source());
            }
            Set<List<String>> alternatives = merged.computeIfAbsent(List.copyOf(source), ignored -> new LinkedHashSet<>());
            for (String alternative : rule.alternatives()) {
                List<String> tokens = compiler.analyze(QueryCompiler.TITLE_FIELD, alternative);
                if (tokens.isEmpty()) {
                    throw new IllegalArgumentException("alternative analyzes to no terms: " + alternative);
                }
                alternatives.add(List.copyOf(tokens));
            }
        }
        Map<List<String>, List<List<String>>> published = new LinkedHashMap<>();
        merged.forEach((source, alternatives) -> published.put(source, List.copyOf(alternatives)));
        return new DeviceTermDictionary(published);
    }

    public ManualQuery expand(ManualQuery query, QueryCompiler compiler) {
        if (query == null) {
            throw new QueryException("query must not be null");
        }
        if (query instanceof ManualQuery.Term term) {
            List<String> source = compiler.analyze(QueryCompiler.TITLE_FIELD, term.text());
            if (source.size() != 1) {
                throw new QueryException("term/prefix query must analyze to exactly one token: " + term.text());
            }
            return expandLeaf(term.field(), source);
        }
        if (query instanceof ManualQuery.Phrase phrase) {
            return expandLeaf(phrase.field(), compiler.analyze(QueryCompiler.TITLE_FIELD, phrase.text()));
        }
        if (query instanceof ManualQuery.Prefix prefix) {
            return prefix;
        }
        if (query instanceof ManualQuery.And and) {
            return new ManualQuery.And(and.children().stream().map(child -> expand(child, compiler)).toList());
        }
        if (query instanceof ManualQuery.Or or) {
            return new ManualQuery.Or(or.children().stream().map(child -> expand(child, compiler)).toList());
        }
        throw new QueryException("unsupported query node: " + query);
    }

    private ManualQuery expandLeaf(Field field, List<String> source) {
        if (source.isEmpty()) {
            throw new QueryException("query text analyzes to no terms");
        }
        return buildAlternativeQuery(field, expandTokens(source));
    }

    private List<List<String>> expandTokens(List<String> source) {
        List<List<String>> paths = List.of(List.of());
        int index = 0;
        while (index < source.size()) {
            int matchedLength = longestMatchLength(source, index);
            if (matchedLength == 0) {
                paths = combine(paths, List.of(List.of(source.get(index))));
                index++;
            } else {
                List<List<String>> choices = new ArrayList<>();
                choices.add(List.copyOf(source.subList(index, index + matchedLength)));
                choices.addAll(rules.get(List.copyOf(source.subList(index, index + matchedLength))));
                paths = combine(paths, List.copyOf(new LinkedHashSet<>(choices)));
                index += matchedLength;
            }
        }
        return List.copyOf(new LinkedHashSet<>(paths));
    }

    private int longestMatchLength(List<String> tokens, int start) {
        int max = rules.keySet().stream().mapToInt(List::size).max().orElse(0);
        for (int length = Math.min(tokens.size() - start, max); length > 0; length--) {
            if (rules.containsKey(List.copyOf(tokens.subList(start, start + length)))) {
                return length;
            }
        }
        return 0;
    }

    private List<List<String>> combine(List<List<String>> prefixes, List<List<String>> choices) {
        List<List<String>> combined = new ArrayList<>();
        for (List<String> prefix : prefixes) {
            for (List<String> choice : choices) {
                List<String> path = new ArrayList<>(prefix);
                path.addAll(choice);
                combined.add(List.copyOf(path));
            }
        }
        return combined;
    }

    private ManualQuery buildAlternativeQuery(Field field, List<List<String>> paths) {
        List<ManualQuery> alternatives = paths.stream()
                .<ManualQuery>map(path -> path.size() == 1
                        ? new ManualQuery.Term(field, path.get(0))
                        : new ManualQuery.Phrase(field, String.join(" ", path)))
                .toList();
        return alternatives.size() == 1 ? alternatives.get(0) : new ManualQuery.Or(alternatives);
    }
}
