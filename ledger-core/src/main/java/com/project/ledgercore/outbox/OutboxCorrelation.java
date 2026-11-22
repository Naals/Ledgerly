package com.project.ledgercore.outbox;

import com.project.ledgercore.common.CorrelationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Supplies request metadata to database-written events without leaking it to another pooled transaction. */
@Component
public class OutboxCorrelation {

    private final JdbcClient jdbc;

    public OutboxCorrelation(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bind() {
        String correlationId = CorrelationContext.current();
        jdbc.sql("SELECT set_config('ledgerly.correlation_id', :correlationId, true)")
                .param("correlationId", correlationId == null ? "" : correlationId)
                .query(String.class)
                .single();
    }
}
