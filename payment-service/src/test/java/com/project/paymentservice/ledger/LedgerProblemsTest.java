package com.project.paymentservice.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LedgerProblemsTest {

    @Test
    void readsTheCodeAfterThePrefix() {
        String body = "{\"type\":\"urn:ledgerly:problem:insufficient-funds\",\"title\":\"Unprocessable Entity\",\"status\":422}";

        assertThat(LedgerProblems.codeOf(body)).isEqualTo("insufficient-funds");
    }

    @Test
    void anotherKindOfTypeIsUnknown() {
        assertThat(LedgerProblems.codeOf("{\"type\":\"about:blank\"}")).isEqualTo("unknown-error");
        assertThat(LedgerProblems.codeOf("{\"type\":\"urn:ledgerly:problem:\"}")).isEqualTo("unknown-error");
        assertThat(LedgerProblems.codeOf("{\"title\":\"no type here\"}")).isEqualTo("unknown-error");
    }

    @Test
    void aBodyThatIsNotJsonIsUnknown() {
        assertThat(LedgerProblems.codeOf("<html>Bad gateway</html>")).isEqualTo("unknown-error");
    }

    @Test
    void anEmptyBodyIsUnknown() {
        assertThat(LedgerProblems.codeOf(null)).isEqualTo("unknown-error");
        assertThat(LedgerProblems.codeOf("  ")).isEqualTo("unknown-error");
    }
}