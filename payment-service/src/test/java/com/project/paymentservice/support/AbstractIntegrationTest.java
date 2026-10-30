package com.project.paymentservice.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base class for integration tests: the full application on a real PostgreSQL. The retry job and the Kafka
 * listener are off, so tests decide when those things happen.
 */
@SpringBootTest(properties = {"payments.retry.enabled=false", "payments.kafka.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {
}