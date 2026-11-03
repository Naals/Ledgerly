package com.project.ledgercore.hold;

import com.project.ledgercore.common.ConflictException;
import java.util.UUID;

public class HoldNotActiveException extends ConflictException {
    public HoldNotActiveException(UUID holdId, HoldStatus status) {
        super("hold-not-active", "Hold " + holdId + " is already " + status);
    }
}