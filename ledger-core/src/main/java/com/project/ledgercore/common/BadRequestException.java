package com.project.ledgercore.common;

/** The request itself is invalid (maps to HTTP 400). */
public class BadRequestException extends LedgerException {

    public BadRequestException(String code, String message) {
        super(code, message);
    }
}
