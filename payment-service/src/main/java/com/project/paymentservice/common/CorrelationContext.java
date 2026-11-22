package com.project.paymentservice.common;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** A validated correlation id scoped to the current thread, restoring the caller's context on close. */
public final class CorrelationContext implements AutoCloseable {

    public static final String HEADER = "X-Correlation-Id";
    public static final String KAFKA_HEADER = "correlation-id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final String previous;
    private final String id;
    private boolean closed;

    private CorrelationContext(String incoming) {
        previous = MDC.get(MDC_KEY);
        id = isValid(incoming) ? incoming : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
    }

    public static boolean isValid(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }

    /** The current safe id, or null when no valid context is active. */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return isValid(value) ? value : null;
    }

    /** Opens an incoming request/event context, generating an id if the input is missing or unsafe. */
    public static CorrelationContext open(String incoming) {
        return new CorrelationContext(incoming);
    }

    /** Preserves an active context; otherwise uses the supplied stable background-operation id. */
    public static CorrelationContext openOrCurrent(String fallback) {
        String current = current();
        return new CorrelationContext(current != null ? current : fallback);
    }

    public String id() {
        return id;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
