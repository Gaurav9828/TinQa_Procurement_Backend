package com.tinqa.procurement.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Procurement signs every token with an Ed25519 private key (EdDSA). Other services (Ecommerce) verify them with the
 * public key only, so they cannot create admin tokens. The public key is published at GET /auth/jwks.
 *
 * @param privateKey        base64 PKCS#8 DER Ed25519 private key (TINQA_SECURITY_JWT_PRIVATE_KEY); required in UAT/prod
 * @param publicKey         base64 X.509 DER Ed25519 public key that matches it (TINQA_SECURITY_JWT_PUBLIC_KEY)
 * @param devKeyFile        dev only: when no key is configured, a pair is generated once and kept in this file, so
 *                          restarts keep sessions
 * @param allowGeneratedKey dev/test only: without configured keys, generate a pair (persisted to devKeyFile when set,
 *                          otherwise kept in memory). False in UAT/prod: a missing key stops the application starting
 * @param expiration        token lifetime in milliseconds
 */
@ConfigurationProperties(prefix = "tinqa.security.jwt")
public record JwtProperties(String privateKey, String publicKey, String devKeyFile, boolean allowGeneratedKey,
                            long expiration) {
}
