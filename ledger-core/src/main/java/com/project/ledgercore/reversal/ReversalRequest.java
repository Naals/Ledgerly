package com.project.ledgercore.reversal;

import jakarta.validation.constraints.Size;

/** The optional body of a reversal request. */
public record ReversalRequest(@Size(max = 200) String reason) {
}