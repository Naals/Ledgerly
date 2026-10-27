package com.project.ledgercore.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base class for integration tests: full application context on top of a real PostgreSQL.
 * The outbox relay is off, so tests decide when events are delivered.
 */
@SpringBootTest(properties = "ledger.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {
}