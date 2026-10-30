package com.project.paymentservice.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/** Reads the machine-readable code out of an RFC 9457 problem document sent by the ledger. */
final class LedgerProblems {

    static final String TYPE_PREFIX = "urn:ledgerly:problem:";
    static final String UNKNOWN = "unknown-error";

    private static final ObjectMapper JSON = new ObjectMapper();

    private LedgerProblems() {
    }

    /** For {@code {"type": "urn:ledgerly:problem:insufficient-funds"}} this returns {@code insufficient-funds}. */
    static String codeOf(String body) {
        if (body == null || body.isBlank()) {
            return UNKNOWN;
        }
        try {
            JsonNode type = JSON.readTree(body).get("type");
            if (type != null && type.isTextual()
                    && type.asText().startsWith(TYPE_PREFIX) && type.asText().length() > TYPE_PREFIX.length()) {
                return type.asText().substring(TYPE_PREFIX.length());
            }
        } catch (IOException e) {
            // not JSON: fall through to the unknown code
        }
        return UNKNOWN;
    }
}