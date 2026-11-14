package com.project.paymentservice.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.paymentservice.ledger.HoldState;
import java.io.IOException;

/**
 * Reads ledger hold events. Like the other parsers it ignores fields it does not know, but it refuses an event it cannot
 * trust: a status it has never heard of, or a capture that names no transaction. Those are not retried (they will not
 * improve) and end up on the dead-letter topic, where the monitor counts them.
 */
public final class HoldEventParser {

    private static final int SUPPORTED_VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private HoldEventParser() {
    }

    public static HoldEvent parse(String json) {
        final HoldEvent event;
        try {
            event = JSON.readValue(json, HoldEvent.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unreadable HoldStatusChanged event", e);
        }
        if (event.version() != SUPPORTED_VERSION) {
            throw new IllegalArgumentException("Unsupported HoldStatusChanged version: " + event.version());
        }
        if (event.holdId() == null || event.status() == null) {
            throw new IllegalArgumentException("Not a HoldStatusChanged event: holdId or status is missing");
        }
        final HoldState state;   // throws IllegalArgumentException for a status this service does not know

        try {
            state = event.state();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown hold status: " + event.status(), e);
        }

        if (state == HoldState.CAPTURED && event.ledgerTransactionId() == null) {
            throw new IllegalArgumentException("A CAPTURED hold event must name the transaction that captured it");
        }
        return event;
    }
}