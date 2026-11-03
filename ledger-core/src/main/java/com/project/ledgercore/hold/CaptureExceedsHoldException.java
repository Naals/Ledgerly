package com.project.ledgercore.hold;

import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.Money;

public class CaptureExceedsHoldException extends BusinessRuleException {
    public CaptureExceedsHoldException(Money held, Money requested) {
        super("capture-exceeds-hold", "Cannot capture " + requested + " from a hold of " + held);
    }
}