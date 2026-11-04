package com.project.paymentservice.common;

/** Something this service depends on cannot answer right now; the same request may be repeated (maps to HTTP 503). */
public class ServiceUnavailableException extends ServiceException {

    public ServiceUnavailableException(String code, String message) {
        super(code, message);
    }
}