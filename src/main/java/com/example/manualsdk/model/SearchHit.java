package com.example.manualsdk.model;

import java.util.List;

/**
 * One search result: the stored document, its BM25 score and the exact
 * matched ranges (UTF-16, half-open) inside the original field texts.
 */
public record SearchHit(String id, float score, String title, String body, List<HitRange> ranges) {

    public SearchHit {
        ranges = List.copyOf(ranges);
    }
}
