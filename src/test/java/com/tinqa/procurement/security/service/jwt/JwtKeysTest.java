package com.tinqa.procurement.security.service.jwt;

import com.tinqa.procurement.security.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeysTest {

    @Test
    void uatAndProdRefuseToStartWithoutAConfiguredKey() {
        assertThatThrownBy(() -> new JwtKeys(new JwtProperties(null, null, null, false, 1000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TINQA_SECURITY_JWT_PRIVATE_KEY");
    }

    @Test
    void halfAPairIsRefused() {
        String publicOnly = Base64.getEncoder().encodeToString(JwtKeys.generate().getPublic().getEncoded());
        assertThatThrownBy(() -> new JwtKeys(new JwtProperties(null, publicOnly, null, true, 1000)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aConfiguredPairIsUsedAndOnlyItsPublicPartIsPublished() {
        KeyPair pair = JwtKeys.generate();
        Base64.Encoder base64 = Base64.getEncoder();
        JwtKeys keys = new JwtKeys(new JwtProperties(base64.encodeToString(pair.getPrivate().getEncoded()),
                base64.encodeToString(pair.getPublic().getEncoded()), null, false, 1000));

        assertThat(keys.publicKey()).isEqualTo(pair.getPublic());
        @SuppressWarnings("unchecked")
        Map<String, Object> jwk = ((List<Map<String, Object>>) keys.jwks().get("keys")).getFirst();
        assertThat(jwk).containsEntry("kty", "OKP").containsEntry("crv", "Ed25519").containsEntry("kid", keys.keyId())
                .doesNotContainKey("d");
        assertThat(Base64.getUrlDecoder().decode((String) jwk.get("x"))).hasSize(32);
    }

    @Test
    void theDevKeyFileKeepsTheSameKeyAcrossRestarts(@TempDir Path dir) {
        JwtProperties dev = new JwtProperties(null, null, dir.resolve("nested/dev-key").toString(), true, 1000);
        JwtKeys first = new JwtKeys(dev);
        JwtKeys second = new JwtKeys(dev);
        assertThat(second.keyId()).isEqualTo(first.keyId());
        assertThat(second.publicKey()).isEqualTo(first.publicKey());
    }
}
