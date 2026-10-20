package com.project.ledgercore.common;

/** The request is valid but a business rule forbids it (maps to HTTP 422). */
public class BusinessRuleException extends LedgerException {

    public BusinessRuleException(String code, String message) {
        super(code, message);
    }
}
