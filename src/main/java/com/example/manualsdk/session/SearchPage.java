package com.example.manualsdk.session;

import com.example.manualsdk.model.SearchHit;

import java.util.List;

/**
 * One page of hits. {@code hasMore} tells whether another page may follow.
 */
public record SearchPage(List<SearchHit> hits, int pageNumber, boolean hasMore) {

    public SearchPage {
        hits = List.copyOf(hits);
    }
}
