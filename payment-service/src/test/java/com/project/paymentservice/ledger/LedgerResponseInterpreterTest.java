package com.project.paymentservice.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerResponseInterpreterTest {

    private static String problem(String code) {
        return "{\"type\":\"urn:ledgerly:problem:" + code + "\"}";
    }

    @Test
    void aCreatedTransferIsCompletedWithItsTransactionId() {
        UUID transactionId = UUID.randomUUID();

        LedgerResult result = LedgerResponseInterpreter.interpret(201,
                "{\"transactionId\":\"" + transactionId + "\",\"amountMinor\":100}");

        assertThat(result).isEqualTo(new LedgerResult.Completed(transactionId));
    }

    @Test
    void aReplayedAnswerCountsAsCompletedToo() {
        UUID transactionId = UUID.randomUUID();

        assertThat(LedgerResponseInterpreter.interpret(200, "{\"transactionId\":\"" + transactionId + "\"}"))
                .isEqualTo(new LedgerResult.Completed(transactionId));
    }

    @Test
    void aSuccessWithoutAUsableTransactionIdCannotBeTrusted() {
        assertThat(LedgerResponseInterpreter.interpret(201, "{}")).isInstanceOf(LedgerResult.Unavailable.class);
        assertThat(LedgerResponseInterpreter.interpret(201, "not json")).isInstanceOf(LedgerResult.Unavailable.class);
        assertThat(LedgerResponseInterpreter.interpret(201, "{\"transactionId\":\"nope\"}"))
                .isInstanceOf(LedgerResult.Unavailable.class);
        assertThat(LedgerResponseInterpreter.interpret(201, null)).isInstanceOf(LedgerResult.Unavailable.class);
    }

    @Test
    void businessRefusalsAreRejectedForGoodWithTheirCode() {
        assertThat(LedgerResponseInterpreter.interpret(422, problem("insufficient-funds")))
                .isEqualTo(new LedgerResult.Rejected("insufficient-funds"));
        assertThat(LedgerResponseInterpreter.interpret(409, problem("account-not-active")))
                .isEqualTo(new LedgerResult.Rejected("account-not-active"));
        assertThat(LedgerResponseInterpreter.interpret(404, problem("account-not-found")))
                .isEqualTo(new LedgerResult.Rejected("account-not-found"));
        assertThat(LedgerResponseInterpreter.interpret(400, problem("validation-failed")))
                .isEqualTo(new LedgerResult.Rejected("validation-failed"));
    }

    @Test
    void aRefusalWithoutAProblemBodyIsRejectedAsUnknown() {
        assertThat(LedgerResponseInterpreter.interpret(422, "boom"))
                .isEqualTo(new LedgerResult.Rejected("unknown-error"));
    }

    @Test
    void serverErrorsAndOverloadLeaveTheOutcomeUnknown() {
        for (int status : new int[] {500, 502, 503, 504, 429, 408}) {
            assertThat(LedgerResponseInterpreter.interpret(status, problem("whatever")))
                    .as("status " + status).isInstanceOf(LedgerResult.Unavailable.class);
        }
        assertThat(LedgerResponseInterpreter.interpret(302, null)).isInstanceOf(LedgerResult.Unavailable.class);
    }
}