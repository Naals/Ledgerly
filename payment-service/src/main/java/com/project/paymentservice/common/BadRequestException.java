package com.project.paymentservice.common;

/** The request itself is invalid (maps to HTTP 400). */
public class BadRequestException extends ServiceException {

    public BadRequestException(String code, String message) {
        super(code, message);
    }
}