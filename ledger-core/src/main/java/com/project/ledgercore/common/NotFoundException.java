package com.project.ledgercore.common;

/** A referenced resource does not exist (maps to HTTP 404). */
public class NotFoundException extends LedgerException {

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
