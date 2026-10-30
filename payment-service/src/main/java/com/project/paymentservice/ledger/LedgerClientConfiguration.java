package com.project.paymentservice.ledger;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class LedgerClientConfiguration {

    @Bean
    LedgerGateway ledgerGateway(RestClient.Builder builder,
                                @Value("${payments.ledger.base-url}") String baseUrl,
                                @Value("${payments.ledger.timeout-ms:3000}") long timeoutMs) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build());
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return new LedgerClient(builder.baseUrl(baseUrl).requestFactory(requestFactory).build());
    }
}