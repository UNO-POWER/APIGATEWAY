package com.unopower.apigateway.filter;

import com.unopower.apigateway.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * EC-05 (Seguridad): valida JWT antes de enrutar. Endpoints publicos (registro/login/actuator)
 * pasan sin token. El resto exige Authorization: Bearer <token> valido; si falta o es invalido,
 * responde 401 sin llegar a los servicios de negocio.
 */
@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationGlobalFilter.class);

    public static final String USER_ID_HEADER = "X-User-Id";

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/usuarios/registro",
            "/api/usuarios/login",
            "/api/usuarios/guest",
            "/actuator"
    );

    public static final String USERNAME_HEADER = "X-Username";

    private final JwtProperties jwtProperties;

    public JwtAuthenticationGlobalFilter(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return reject(exchange, "falta encabezado Authorization Bearer");
        }

        String token = authHeader.substring("Bearer ".length());
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            Claims claims = jws.getPayload();
            String userId = claims.getSubject();
            String username = claims.get("username", String.class);

            ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate()
                    .header(USER_ID_HEADER, userId);
            if (username != null) {
                requestBuilder.header(USERNAME_HEADER, username);
            }
            ServerHttpRequest mutatedRequest = requestBuilder.build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("JWT invalido en path={}: {}", path, ex.getMessage());
            return reject(exchange, "token invalido o expirado");
        }
    }

    private boolean isPublic(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> reject(ServerWebExchange exchange, String reason) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("X-Auth-Rejected-Reason", reason);
        return response.setComplete();
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
