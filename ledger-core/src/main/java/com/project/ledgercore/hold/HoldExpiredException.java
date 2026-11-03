package com.project.ledgercore.hold;

import com.project.ledgercore.common.ConflictException;
import java.util.UUID;

public class HoldExpiredException extends ConflictException {
    public HoldExpiredException(UUID holdId) {
        super("hold-expired", "Hold " + holdId + " has expired");
    }
}