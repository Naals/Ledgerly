package com.project.paymentservice.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * Reads ledger events. It is deliberately tolerant: fields it does not know are ignored, so the ledger can
 * add fields to an event without breaking this consumer.
 */
public final class LedgerEventParser {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private LedgerEventParser() {
    }

    public static LedgerEvent parse(String json) {
        try {
            LedgerEvent event = JSON.readValue(json, LedgerEvent.class);
            if (event.transactionId() == null || event.type() == null) {
                throw new IllegalArgumentException("Not a TransactionPosted event: transactionId or type is missing");
            }
            return event;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unreadable TransactionPosted event", e);
        }
    }
}