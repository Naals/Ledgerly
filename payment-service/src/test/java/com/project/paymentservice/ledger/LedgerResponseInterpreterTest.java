package com.project.paymentservice.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerResponseInterpreterTest {

    private static final UUID HOLD = UUID.fromString("0f3b6a52-7c1e-4a0d-9d55-3b1c8d2e4a10");
    private static final UUID TRANSACTION = UUID.fromString("7d1f6a0e-2b1c-4c58-9a57-0c6f0d9a1b11");

    private static String hold(String status, String transaction) {
        return "{\"id\":\"" + HOLD + "\",\"accountId\":\"" + UUID.randomUUID() + "\",\"amountMinor\":100000,"
                + "\"currency\":\"KZT\",\"status\":\"" + status + "\",\"reference\":\"p-1\","
                + "\"expiresAt\":\"2026-11-03T09:30:00.123456+05:00\",\"capturedMinor\":null,"
                + "\"ledgerTransactionId\":" + (transaction == null ? "null" : "\"" + transaction + "\"") + "}";
    }

    @Test
    void aPlacedHoldIsReadWithItsStateAndExpiry() {
        LedgerOutcome<HoldInfo> outcome = LedgerResponseInterpreter.interpretHold(201, hold("ACTIVE", null));

        assertThat(outcome).isEqualTo(new LedgerOutcome.Done<>(new HoldInfo(
                HOLD, HoldState.ACTIVE, OffsetDateTime.parse("2026-11-03T09:30:00.123456+05:00"), null)));
    }

    @Test
    void aCapturedHoldCarriesTheTransactionThatCapturedIt() {
        LedgerOutcome<HoldInfo> outcome =
                LedgerResponseInterpreter.interpretHold(200, hold("CAPTURED", TRANSACTION.toString()));

        assertThat(outcome).isInstanceOfSatisfying(LedgerOutcome.Done.class,
                done -> assertThat(((HoldInfo) done.value()).ledgerTransactionId()).isEqualTo(TRANSACTION));
    }

    @Test
    void everyHoldStateIsUnderstood() {
        for (HoldState state : HoldState.values()) {
            assertThat(LedgerResponseInterpreter.interpretHold(200, hold(state.name(), null)))
                    .isInstanceOf(LedgerOutcome.Done.class);
        }
    }

    @Test
    void aHoldWeCannotReadIsNotTrusted() {
        for (String body : new String[] {null, "", "not json", "{}", hold("SOMETHING_NEW", null),
                hold("ACTIVE", null).replace("\"expiresAt\":\"2026-11-03T09:30:00.123456+05:00\"", "\"expiresAt\":\"soon\""),
                hold("ACTIVE", null).replace(HOLD.toString(), "not-a-uuid")}) {
            assertThat(LedgerResponseInterpreter.interpretHold(201, body))
                    .as("body: " + body)
                    .isInstanceOf(LedgerOutcome.Unavailable.class);
        }
    }

    @Test
    void aCaptureIsTheTransactionTheLedgerPosted() {
        assertThat(LedgerResponseInterpreter.interpretCapture(201,
                "{\"holdId\":\"" + HOLD + "\",\"transactionId\":\"" + TRANSACTION + "\",\"capturedMinor\":100000}"))
                .isEqualTo(new LedgerOutcome.Done<>(TRANSACTION));
        assertThat(LedgerResponseInterpreter.interpretCapture(201, "{\"capturedMinor\":1}"))
                .isInstanceOf(LedgerOutcome.Unavailable.class);
    }

    @Test
    void aHoldRefusalIsFinalAndKeepsTheLedgersCode() {
        assertThat(LedgerResponseInterpreter.interpretHold(422, problem("insufficient-funds")))
                .isEqualTo(new LedgerOutcome.Rejected<HoldInfo>("insufficient-funds"));
        assertThat(LedgerResponseInterpreter.interpretCapture(409, problem("hold-expired")))
                .isEqualTo(new LedgerOutcome.Rejected<UUID>("hold-expired"));
        assertThat(LedgerResponseInterpreter.interpretCapture(409, problem("hold-not-active")))
                .isEqualTo(new LedgerOutcome.Rejected<UUID>("hold-not-active"));
        assertThat(LedgerResponseInterpreter.interpretHold(404, null))
                .isEqualTo(new LedgerOutcome.Rejected<HoldInfo>("unknown-error"));
    }

    @Test
    void anAnswerThatLeavesTheHoldOutcomeUnknownIsNotFinal() {
        for (int status : new int[] {408, 429, 500, 502, 503, 504, 302}) {
            assertThat(LedgerResponseInterpreter.interpretHold(status, null)).as("hold " + status)
                    .isInstanceOf(LedgerOutcome.Unavailable.class);
            assertThat(LedgerResponseInterpreter.interpretCapture(status, null)).as("capture " + status)
                    .isInstanceOf(LedgerOutcome.Unavailable.class);
        }
    }

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