package com.project.paymentservice.events;

import com.project.paymentservice.ledger.HoldState;
import com.project.paymentservice.payment.PaymentStatus;

/**
 * What a hold's new state means for the payment it belongs to.
 *
 * <p>Only an AUTHORIZED payment is ever changed by a hold event. For every other payment the event is either news this
 * service already has (it recorded the outcome itself, which is the usual case: it asked for it), news it has to wait
 * for (the authorization is not recorded yet), or a contradiction between the two services that must not be hidden:
 * money moved for a payment this service considers dead, or a payment this service considers paid whose hold the ledger
 * says was given back.
 */
enum HoldEventAction {
    /** The hold was captured and the payment is AUTHORIZED: make it COMPLETED. */
    COMPLETE,
    /** The hold was released and the payment is AUTHORIZED: make it CANCELLED. */
    CANCEL,
    /** The hold expired and the payment is AUTHORIZED: make it EXPIRED. */
    EXPIRE,
    /** The payment already reflects this (or is over for a reason that moved no money): nothing to do. */
    ALREADY_APPLIED,
    /** The payment is still PENDING: its authorization is not recorded yet. Fail so the event comes again later. */
    WAIT_FOR_AUTHORIZATION,
    /** The two services disagree about money. Raise it. */
    INCONSISTENT,
    /** The hold is (still) ACTIVE: nothing has happened that this service acts on. */
    NOTHING_TO_DO;

    static HoldEventAction decide(HoldState hold, PaymentStatus payment) {
        if (hold == HoldState.ACTIVE) {
            return NOTHING_TO_DO;
        }
        if (payment == PaymentStatus.PENDING) {
            return WAIT_FOR_AUTHORIZATION;
        }
        return switch (hold) {
            case CAPTURED -> switch (payment) {
                case AUTHORIZED -> COMPLETE;
                case COMPLETED, REVERSED -> ALREADY_APPLIED;
                case FAILED, CANCELLED, EXPIRED -> INCONSISTENT;   // the money moved for a payment we think is dead
                case PENDING -> WAIT_FOR_AUTHORIZATION;
            };
            case RELEASED -> switch (payment) {
                case AUTHORIZED -> CANCEL;
                case CANCELLED, EXPIRED, FAILED -> ALREADY_APPLIED;   // over already, and no money moved
                case COMPLETED, REVERSED -> INCONSISTENT;             // we say it was paid; the ledger says the hold was given back
                case PENDING -> WAIT_FOR_AUTHORIZATION;
            };
            case EXPIRED -> switch (payment) {
                case AUTHORIZED -> EXPIRE;
                case CANCELLED, EXPIRED, FAILED -> ALREADY_APPLIED;
                case COMPLETED, REVERSED -> INCONSISTENT;
                case PENDING -> WAIT_FOR_AUTHORIZATION;
            };
            case ACTIVE -> NOTHING_TO_DO;
        };
    }
}