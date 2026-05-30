package com.demo.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.Map;

/**
 * Auth Service — RS256 JWTs, BCrypt passwords, access + refresh tokens.
 *
 *  POST /auth/login   -> { accessToken (short), refreshToken (long) }
 *  POST /auth/refresh -> { accessToken } from a valid refresh token
 *  GET  /auth/public-key -> the RSA public key (gateway verifies with this)
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtKeys keys;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final long accessTtl;
    private final long refreshTtl;

    // username -> { bcryptHash, role }. Passwords are stored HASHED, never plaintext.
    private final Map<String, String[]> users;

    public AuthController(JwtKeys keys,
                          @Value("${jwt.access-ttl-seconds:300}") long accessTtl,
                          @Value("${jwt.refresh-ttl-seconds:604800}") long refreshTtl) {
        this.keys = keys;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.users = Map.of(
                "user",  new String[]{encoder.encode("password"), "USER"},
                "admin", new String[]{encoder.encode("admin123"), "ADMIN"}
        );
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String[] record = users.get(username);
        if (record == null || !encoder.matches(body.get("password"), record[0])) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        String role = record[1];
        return Map.of(
                "accessToken", token(username, role, "access", accessTtl),
                "refreshToken", token(username, role, "refresh", refreshTtl),
                "tokenType", "Bearer",
                "user", username,
                "role", role,
                "expiresInSeconds", accessTtl
        );
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody Map<String, String> body) {
        Claims c;
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(keys.publicKey()).build()
                    .parseSignedClaims(body.get("refreshToken"));
            c = jws.getPayload();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }
        if (!"refresh".equals(c.get("type", String.class))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not a refresh token");
        }
        String username = c.getSubject();
        String role = c.get("role", String.class);
        return Map.of(
                "accessToken", token(username, role, "access", accessTtl),
                "tokenType", "Bearer",
                "expiresInSeconds", accessTtl
        );
    }

    @GetMapping("/public-key")
    public Map<String, String> publicKey() {
        return Map.of("publicKey", keys.publicKeyBase64(), "alg", "RS256");
    }

    private String token(String username, String role, String type, long ttlSeconds) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .claim("type", type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlSeconds * 1000))
                .signWith(keys.privateKey())   // RS256 (RSA private key)
                .compact();
    }
}