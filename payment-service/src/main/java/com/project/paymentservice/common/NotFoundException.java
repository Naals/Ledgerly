package com.project.paymentservice.common;

/** A referenced resource does not exist (maps to HTTP 404). */
public class NotFoundException extends ServiceException {

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}