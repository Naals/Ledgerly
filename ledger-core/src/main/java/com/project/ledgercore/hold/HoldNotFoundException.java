package com.project.ledgercore.hold;

import com.project.ledgercore.common.NotFoundException;
import java.util.UUID;

public class HoldNotFoundException extends NotFoundException {
    public HoldNotFoundException(UUID holdId) {
        super("hold-not-found", "Hold " + holdId + " does not exist");
    }
}