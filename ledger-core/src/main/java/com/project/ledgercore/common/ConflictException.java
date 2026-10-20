package com.project.ledgercore.common;

/** The request is valid but clashes with the current state of the resource (maps to HTTP 409). */
public class ConflictException extends LedgerException {

    public ConflictException(String code, String message) {
        super(code, message);
    }
}