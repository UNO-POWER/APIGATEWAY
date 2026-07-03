package com.unopower.apigateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * HU-10: genera/propaga X-Trace-Id para correlacionar logs entre servicios.
 * Corre antes que la validacion JWT (order mas bajo = mayor prioridad).
 */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final Logger log = LoggerFactory.getLogger(TraceIdGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(TRACE_ID_HEADER, traceId)
                .build();

        MDC.put("traceId", traceId);
        log.info("request path={} method={}", mutatedRequest.getPath(), mutatedRequest.getMethod());
        MDC.remove("traceId");

        exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, traceId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
