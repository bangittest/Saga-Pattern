package com.demo.auth;

import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Holds the RSA key pair used for RS256 (asymmetric) signing.
 *
 * Only auth-service has the PRIVATE key (can mint tokens). The gateway fetches the
 * PUBLIC key and can only VERIFY — it can never forge a token. This is the key
 * advantage of RS256 over the shared-secret HS256 we used before.
 *
 * The pair is generated at startup. Restarting auth-service rotates the keys (the
 * gateway re-fetches on the next verification failure). Production would persist the
 * key / expose a JWKS endpoint with key ids for smooth rotation.
 */
@Component
public class JwtKeys {

    private final KeyPair keyPair;

    public JwtKeys() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            this.keyPair = gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("cannot generate RSA key pair", e);
        }
    }

    public PrivateKey privateKey() { return keyPair.getPrivate(); }
    public PublicKey publicKey() { return keyPair.getPublic(); }

    /** Base64 of the X.509 (SubjectPublicKeyInfo) encoding — what the gateway consumes. */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}