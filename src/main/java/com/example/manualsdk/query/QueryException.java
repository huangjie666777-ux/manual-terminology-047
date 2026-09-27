package com.example.manualsdk.query;

/** Raised for empty queries or illegally structured query trees. */
public class QueryException extends RuntimeException {

    public QueryException(String message) {
        super(message);
    }
}
