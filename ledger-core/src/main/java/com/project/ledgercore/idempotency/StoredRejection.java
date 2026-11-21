package com.project.ledgercore.idempotency;

import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.ConflictException;
import com.project.ledgercore.common.LedgerException;
import com.project.ledgercore.common.NotFoundException;

/** The stable API fields of an expected rejection, without serializing Java exception classes. */
record StoredRejection(int status, String code, String message) {

    static StoredRejection from(LedgerException exception) {
        int status;
        if (exception instanceof BadRequestException) {
            status = 400;
        } else if (exception instanceof NotFoundException) {
            status = 404;
        } else if (exception instanceof ConflictException) {
            status = 409;
        } else if (exception instanceof BusinessRuleException) {
            status = 422;
        } else {
            throw exception; // Unknown failures must not become permanent outcomes.
        }
        return new StoredRejection(status, exception.code(), exception.getMessage());
    }

    IdempotentRejectionException asException(boolean replayed) {
        return new IdempotentRejectionException(status, code, message, replayed);
    }
}
