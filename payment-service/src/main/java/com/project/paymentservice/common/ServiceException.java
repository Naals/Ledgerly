package com.project.paymentservice.common;

/** Base type for expected failures. Each one carries a stable, machine-readable code. */
public abstract class ServiceException extends RuntimeException {

    private final String code;

    protected ServiceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}