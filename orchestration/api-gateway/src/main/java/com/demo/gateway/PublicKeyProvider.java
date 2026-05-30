package com.demo.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches and caches the RSA PUBLIC key from auth-service (/auth/public-key).
 *
 * The gateway only ever holds the public key, so it can verify tokens but never
 * mint them. Cached after the first fetch; refresh() forces a re-fetch (used when a
 * verification fails because auth-service rotated its keys on restart).
 */
@Component
public class PublicKeyProvider {

    private final String authUrl;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicReference<PublicKey> cached = new AtomicReference<>();

    public PublicKeyProvider(@Value("${AUTH_URL:http://localhost:8085}") String authUrl, ObjectMapper json) {
        this.authUrl = authUrl;
        this.json = json;
    }

    public PublicKey get() {
        PublicKey k = cached.get();
        return k != null ? k : refresh();
    }

    public synchronized PublicKey refresh() {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(authUrl + "/auth/public-key")).GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                String b64 = json.readTree(res.body()).get("publicKey").asText();
                byte[] der = Base64.getDecoder().decode(b64);
                PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
                cached.set(key);
                return key;
            } catch (Exception e) {
                last = new IllegalStateException("cannot fetch public key from " + authUrl, e);
                try { Thread.sleep(500L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        throw last;
    }
}