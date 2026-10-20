package com.project.ledgercore.api;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class CorrelationIdIT extends AbstractIntegrationTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    MockMvc mvc;

    @Test
    void echoesASafeIncomingId() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID()).header("X-Correlation-Id", "req-42"))
                .andExpect(header().string("X-Correlation-Id", "req-42"));
    }

    @Test
    void generatesAnIdWhenNoneIsSent() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID()))
                .andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
    }

    @Test
    void replacesAnUnsafeIncomingId() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID()).header("X-Correlation-Id", "bad id; drop table"))
                .andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
    }
}