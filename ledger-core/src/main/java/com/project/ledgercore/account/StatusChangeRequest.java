package com.project.ledgercore.account;

import jakarta.validation.constraints.Size;

/** The optional body of freeze, unfreeze and close. */
public record StatusChangeRequest(@Size(max = 255) String reason) {
}