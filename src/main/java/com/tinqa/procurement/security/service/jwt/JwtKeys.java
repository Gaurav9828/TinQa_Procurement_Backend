package com.tinqa.procurement.security.service.jwt;

import com.tinqa.procurement.security.config.JwtProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The Ed25519 key pair tokens are signed with, its key id ("kid": the first 16 bytes of the SHA-256 of the public key,
 * base64url) and the public key as a JSON Web Key for GET /auth/jwks. Loaded once at startup.
 */
@Component
public class JwtKeys {

    private static final String ALGORITHM = "Ed25519";
    // An Ed25519 X.509 public key is a fixed 12-byte header followed by the raw 32-byte key (RFC 8410)
    private static final int X509_HEADER_LENGTH = 12;

    private final KeyPair keyPair;
    private final String keyId;
    private final Map<String, Object> jwks;

    public JwtKeys(JwtProperties properties) {
        this.keyPair = load(properties);
        byte[] encoded = keyPair.getPublic().getEncoded();
        this.keyId = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(sha256(encoded), 16));
        String x = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Arrays.copyOfRange(encoded, X509_HEADER_LENGTH, encoded.length));
        this.jwks = Map.of("keys", List.of(Map.of(
                "kty", "OKP", "crv", ALGORITHM, "alg", "EdDSA", "use", "sig", "kid", keyId, "x", x)));
    }

    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    public PublicKey publicKey() {
        return keyPair.getPublic();
    }

    public String keyId() {
        return keyId;
    }

    /** {"keys":[{kty, crv, alg, use, kid, x}]}: only public data. */
    public Map<String, Object> jwks() {
        return jwks;
    }

    private static KeyPair load(JwtProperties properties) {
        if (notBlank(properties.privateKey()) || notBlank(properties.publicKey())) {
            if (!notBlank(properties.privateKey()) || !notBlank(properties.publicKey())) {
                throw new IllegalStateException(
                        "Set both TINQA_SECURITY_JWT_PRIVATE_KEY and TINQA_SECURITY_JWT_PUBLIC_KEY (base64 DER Ed25519).");
            }
            return decode(properties.privateKey(), properties.publicKey());
        }
        if (!properties.allowGeneratedKey()) {
            throw new IllegalStateException("No token signing key: set TINQA_SECURITY_JWT_PRIVATE_KEY and "
                    + "TINQA_SECURITY_JWT_PUBLIC_KEY (an Ed25519 pair, base64 DER).");
        }
        return notBlank(properties.devKeyFile()) ? loadOrCreate(Path.of(properties.devKeyFile())) : generate();
    }

    // Dev: line 1 = private key, line 2 = public key (base64 DER); the file is readable by its owner only
    private static KeyPair loadOrCreate(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
                if (lines.size() >= 2) {
                    return decode(lines.get(0).trim(), lines.get(1).trim());
                }
            }
            KeyPair generated = generate();
            Path directory = file.toAbsolutePath().getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Base64.Encoder base64 = Base64.getEncoder();
            Files.writeString(file, base64.encodeToString(generated.getPrivate().getEncoded()) + "\n"
                    + base64.encodeToString(generated.getPublic().getEncoded()) + "\n", StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX file system (Windows): the user's home folder protects it
            }
            return generated;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read or write the dev token key file " + file, exception);
        }
    }

    static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Ed25519 is not available in this JVM", exception);
        }
    }

    private static KeyPair decode(String privateKey, String publicKey) {
        try {
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            PrivateKey key = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.trim())));
            PublicKey pub = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.trim())));
            return new KeyPair(pub, key);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("The token signing keys are not a valid base64 DER Ed25519 pair", exception);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
