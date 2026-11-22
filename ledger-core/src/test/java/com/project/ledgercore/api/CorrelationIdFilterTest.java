package com.project.ledgercore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.common.CorrelationContext;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearContext() {
        MDC.remove(CorrelationContext.MDC_KEY);
    }

    @Test
    void scopesTheIncomingIdAndRestoresThePreviousContext() throws Exception {
        MDC.put(CorrelationContext.MDC_KEY, "parent");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationContext.HEADER, "request-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(CorrelationContext.current()).isEqualTo("request-42"));

        assertThat(response.getHeader(CorrelationContext.HEADER)).isEqualTo("request-42");
        assertThat(CorrelationContext.current()).isEqualTo("parent");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"bad id", "line\nbreak", ""})
    void replacesMissingOrUnsafeHeadersAndCleansUpAfterwards(String incoming) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (incoming != null) {
            request.addHeader(CorrelationContext.HEADER, incoming);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            assertThat(CorrelationContext.current()).matches("[0-9a-f-]{36}");
            assertThat(CorrelationContext.current()).isEqualTo(response.getHeader(CorrelationContext.HEADER));
        });
        assertThat(CorrelationContext.current()).isNull();
    }

    @Test
    void aFailedRequestDoesNotLeakItsIdIntoTheNextRequest() throws Exception {
        MockHttpServletRequest failed = new MockHttpServletRequest();
        failed.addHeader(CorrelationContext.HEADER, "failed-request");
        MockHttpServletResponse failedResponse = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(failed, failedResponse, (req, res) -> {
            assertThat(CorrelationContext.current()).isEqualTo("failed-request");
            throw new ServletException("failed");
        })).isInstanceOf(ServletException.class);
        assertThat(failedResponse.getHeader(CorrelationContext.HEADER)).isEqualTo("failed-request");
        assertThat(CorrelationContext.current()).isNull();

        MockHttpServletResponse nextResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), nextResponse, (req, res) ->
                assertThat(CorrelationContext.current()).isNotEqualTo("failed-request"));
        assertThat(CorrelationContext.current()).isNull();
    }
}
