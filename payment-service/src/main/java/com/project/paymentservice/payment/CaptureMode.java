package com.project.paymentservice.payment;

/** How a payment's money moves: in one step, or held first and captured later. */
public enum CaptureMode {
    /** The ledger transfers the money at once. */
    AUTOMATIC,
    /** The ledger first holds the money (authorization); a later capture moves it, or the hold is released or expires. */
    MANUAL
}