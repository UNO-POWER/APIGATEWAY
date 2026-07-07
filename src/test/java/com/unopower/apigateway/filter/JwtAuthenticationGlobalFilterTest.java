package com.unopower.apigateway.filter;

import com.unopower.apigateway.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EC-05: el gateway es el punto autoritativo de autenticacion REST. Un endpoint
 * protegido sin token debe morir aqui con 401, sin llegar a los servicios.
 */
class JwtAuthenticationGlobalFilterTest {

    private static final String SECRET = "un-secreto-de-pruebas-suficientemente-largo-para-hs512-0123456789";

    private JwtAuthenticationGlobalFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        filter = new JwtAuthenticationGlobalFilter(props);
    }

    private String tokenValido() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("user-1")
                .claim("username", "Ana")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();
    }

    /** Cadena falsa que registra si la request paso el filtro y con que headers. */
    private GatewayFilterChain cadena(AtomicReference<ServerWebExchange> capturado) {
        return exchange -> {
            capturado.set(exchange);
            return Mono.empty();
        };
    }

    @Test
    @DisplayName("Gherkin HU-06: request protegida sin token responde 401 sin tocar los servicios")
    void protegidoSinTokenDa401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/usuarios/me/stats").build());
        AtomicReference<ServerWebExchange> pasado = new AtomicReference<>();

        filter.filter(exchange, cadena(pasado)).block();

        assertNull(pasado.get(), "la request no debio pasar del gateway");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("un token invalido tambien responde 401")
    void protegidoConTokenInvalidoDa401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/salas/ABC123")
                        .header("Authorization", "Bearer basura")
                        .build());
        AtomicReference<ServerWebExchange> pasado = new AtomicReference<>();

        filter.filter(exchange, cadena(pasado)).block();

        assertNull(pasado.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("un token valido pasa y propaga X-User-Id y X-Username a los servicios")
    void tokenValidoPropagaIdentidad() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/salas")
                        .header("Authorization", "Bearer " + tokenValido())
                        .build());
        AtomicReference<ServerWebExchange> pasado = new AtomicReference<>();

        filter.filter(exchange, cadena(pasado)).block();

        assertNotNull(pasado.get());
        assertEquals("user-1", pasado.get().getRequest().getHeaders().getFirst("X-User-Id"));
        assertEquals("Ana", pasado.get().getRequest().getHeaders().getFirst("X-Username"));
    }

    @Test
    @DisplayName("registro, login y guest son publicos y pasan sin token")
    void rutasPublicasPasanSinToken() {
        for (String ruta : new String[]{"/api/usuarios/registro", "/api/usuarios/login", "/api/usuarios/guest"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post(ruta).build());
            AtomicReference<ServerWebExchange> pasado = new AtomicReference<>();

            filter.filter(exchange, cadena(pasado)).block();

            assertNotNull(pasado.get(), ruta + " deberia ser publica");
        }
    }
}
