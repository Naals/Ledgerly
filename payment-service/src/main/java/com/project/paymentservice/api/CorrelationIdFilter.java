package com.project.paymentservice.api;

import com.project.paymentservice.common.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reads or generates a safe correlation id, scopes it to the request and echoes it in the response. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = CorrelationContext.HEADER;
    public static final String MDC_KEY = CorrelationContext.MDC_KEY;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (CorrelationContext context = CorrelationContext.open(request.getHeader(HEADER))) {
            response.setHeader(HEADER, context.id());
            chain.doFilter(request, response);
        }
    }
}
