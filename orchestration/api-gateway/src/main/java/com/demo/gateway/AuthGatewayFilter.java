package com.demo.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Edge authentication + authorization for ALL traffic through the gateway.
 *
 *  - /auth/**                : public (you need it to obtain a token)
 *  - everything else         : must carry a valid "Authorization: Bearer <jwt>"
 *  - raw service data routes : ADMIN role only (RBAC demo)
 *
 * The user/role from the verified token are forwarded downstream as headers, so
 * internal services can trust them without re-validating the JWT (stateless auth).
 */
@Component
public class AuthGatewayFilter implements GlobalFilter, Ordered {

    // Routes that expose raw cross-customer data -> restricted to ADMIN.
    private static final List<String> ADMIN_ONLY = List.of(
            "/order-service", "/payment-service", "/inventory-service");

    private final PublicKeyProvider keys;

    public AuthGatewayFilter(PublicKeyProvider keys) {
        this.keys = keys;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Public: login/registration.
        if (path.startsWith("/auth")) {
            return chain.filter(exchange);
        }

        // 1) Authentication — require a valid Bearer token.
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return deny(exchange, HttpStatus.UNAUTHORIZED);
        }
        String jwt = auth.substring(7);
        Claims claims = verify(jwt);
        if (claims == null) {
            return deny(exchange, HttpStatus.UNAUTHORIZED);
        }
        // Access tokens only — a refresh token must not be usable as a bearer credential.
        if (!"access".equals(claims.get("type", String.class))) {
            return deny(exchange, HttpStatus.UNAUTHORIZED);
        }

        String user = claims.getSubject();
        String role = claims.get("role", String.class);

        // 2) Authorization — admin-only routes.
        boolean adminOnly = ADMIN_ONLY.stream().anyMatch(path::startsWith);
        if (adminOnly && !"ADMIN".equals(role)) {
            return deny(exchange, HttpStatus.FORBIDDEN);
        }

        // 3) Forward identity downstream (services trust these headers).
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-User", user == null ? "" : user)
                .header("X-Role", role == null ? "" : role)
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /** Verify with the cached public key; if that fails, re-fetch once (key may have rotated). */
    private Claims verify(String jwt) {
        try {
            return Jwts.parser().verifyWith(keys.get()).build().parseSignedClaims(jwt).getPayload();
        } catch (Exception first) {
            try {
                return Jwts.parser().verifyWith(keys.refresh()).build().parseSignedClaims(jwt).getPayload();
            } catch (Exception second) {
                return null;
            }
        }
    }

    private Mono<Void> deny(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -1;   // run before routing
    }
}