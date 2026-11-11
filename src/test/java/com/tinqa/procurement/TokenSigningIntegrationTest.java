package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tokens are signed with Procurement's Ed25519 private key, and the public key is published at GET /auth/jwks so
 * Ecommerce can verify admin tokens without being able to create them. A token signed with the old shared HMAC secret
 * no longer works.
 */
class TokenSigningIntegrationTest extends IntegrationTestSupport {

    // The Ed25519 X.509 header (RFC 8410): the JWKS "x" value is the raw key after it
    private static final byte[] ED25519_X509_HEADER = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    private static final String OLD_SHARED_SECRET = "TinQaTestJwtSecretKeyMustBeAtLeast32CharactersLong!";

    @Test
    void thePublishedKeyVerifiesALoginTokenAndNeedsNoSignIn() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/auth/jwks")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("public");
        JsonNode key = objectMapper.readTree(result.getResponse().getContentAsString()).path("keys").get(0);
        assertThat(key.path("kty").asText()).isEqualTo("OKP");
        assertThat(key.path("crv").asText()).isEqualTo("Ed25519");
        assertThat(key.has("d")).as("never the private key").isFalse();

        String token = login(l2a.username(), SEED_PASSWORD);
        Jws<Claims> verified = Jwts.parser().verifyWith(publicKey(key.path("x").asText())).build().parseSignedClaims(token);
        assertThat(verified.getHeader().getAlgorithm()).isEqualTo("EdDSA");
        assertThat(verified.getHeader().getKeyId()).isEqualTo(key.path("kid").asText());
        assertThat(verified.getPayload().getIssuer()).isEqualTo("tinqa-procurement");
        assertThat(verified.getPayload().getSubject()).isEqualTo(l2a.username());
    }

    @Test
    void aTokenSignedWithTheOldSharedSecretIsRefused() throws Exception {
        Date now = new Date();
        String forged = Jwts.builder()
                .subject(l2a.username()).id("forged").issuer("tinqa-procurement")
                .claim("role", "ADMIN_L2").claim("authClient", "WEB")
                .issuedAt(now).expiration(new Date(now.getTime() + 600_000))
                .signWith(Keys.hmacShaKeyFor(OLD_SHARED_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        get("/v1/admin/items?size=5", forged, 401);
        get("/v1/admin/items?size=5", l2a.token(), 200);
    }

    private static PublicKey publicKey(String x) throws Exception {
        byte[] raw = Base64.getUrlDecoder().decode(x);
        byte[] encoded = new byte[ED25519_X509_HEADER.length + raw.length];
        System.arraycopy(ED25519_X509_HEADER, 0, encoded, 0, ED25519_X509_HEADER.length);
        System.arraycopy(raw, 0, encoded, ED25519_X509_HEADER.length, raw.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }
}
