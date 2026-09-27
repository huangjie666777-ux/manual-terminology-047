package com.example.manualsdk.model;

/**
 * A matched range inside one stored field, expressed as UTF-16 code-unit
 * offsets, start inclusive, end exclusive.
 */
public record HitRange(String field, int start, int end) implements Comparable<HitRange> {

    public HitRange {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid range [" + start + ", " + end + ")");
        }
    }

    @Override
    public int compareTo(HitRange other) {
        int byField = field.compareTo(other.field);
        if (byField != 0) {
            return byField;
        }
        return start != other.start ? Integer.compare(start, other.start)
                : Integer.compare(end, other.end);
    }
}
