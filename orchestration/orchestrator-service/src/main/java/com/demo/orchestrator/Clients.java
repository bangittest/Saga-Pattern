package com.demo.orchestrator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * One RestClient per downstream service. Built from the auto-configured
 * RestClient.Builder (prototype-scoped) so Micrometer's observation interceptor is
 * attached — that propagates the trace/span ids downstream as W3C headers, giving a
 * single end-to-end trace in Zipkin. (RestClient.create(url) would NOT be traced.)
 */
@Configuration
public class Clients {

    @Bean
    RestClient orderClient(@Value("${ORDER_URL:http://localhost:8081}") String url, RestClient.Builder builder) {
        return builder.baseUrl(url).build();
    }

    @Bean
    RestClient paymentClient(@Value("${PAYMENT_URL:http://localhost:8082}") String url, RestClient.Builder builder) {
        return builder.baseUrl(url).build();
    }

    @Bean
    RestClient inventoryClient(@Value("${INVENTORY_URL:http://localhost:8083}") String url, RestClient.Builder builder) {
        return builder.baseUrl(url).build();
    }
}
