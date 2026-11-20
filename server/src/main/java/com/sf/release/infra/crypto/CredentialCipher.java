package com.sf.release.infra.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Symmetric encryption of CODING credentials: AES-GCM with a fresh 12-byte IV and a
 * 128-bit authentication tag.
 *
 * <p>The key is the Base64-decoded {@code CODING_CRED_KEY} environment variable and must be
 * exactly 32 bytes (AES-256). The constructor is the startup gate: a missing, non-Base64 or
 * wrong-length key throws {@link IllegalStateException}, which aborts Spring's context
 * refresh instead of leaving the application running with a configuration that would
 * silently store reversible "encryption".
 *
 * <p>The stored form is {@code Base64(iv || ciphertext || tag)}. The IV is not secret and
 * is stored as a prefix; the tag makes tampering detectable. No key material or plaintext
 * ever appears in an exception message or a log line.
 */
@Component
public class CredentialCipher {

    /** Environment variable holding the Base64-encoded 32-byte AES key. */
    public static final String KEY_ENVIRONMENT_VARIABLE = "CODING_CRED_KEY";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int MIN_SEALED_BYTES = IV_BYTES + TAG_BYTES;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${CODING_CRED_KEY:}") String base64Key) {
        this.key = new SecretKeySpec(decodeKey(base64Key), "AES");
    }

    /** Encrypts a plaintext credential; every call uses a fresh random IV. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("credential must not be null");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, payload, 0, IV_BYTES);
            System.arraycopy(sealed, 0, payload, IV_BYTES, sealed.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            // Never include the plaintext or key material in the failure message.
            throw new IllegalStateException("credential encryption failed");
        }
    }

    /** Decrypts and authenticates a value produced by {@link #encrypt(String)}. */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("credential ciphertext is malformed");
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("credential ciphertext is not valid Base64");
        }
        if (payload.length < MIN_SEALED_BYTES) {
            throw new IllegalStateException("credential ciphertext is malformed");
        }
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(payload, 0, iv, 0, IV_BYTES);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            // The tag check failed: wrong key or tampered ciphertext.
            throw new IllegalStateException("credential decryption failed");
        }
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(KEY_ENVIRONMENT_VARIABLE
                + " is not set; provide a Base64-encoded 32-byte AES key");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(KEY_ENVIRONMENT_VARIABLE + " must be valid Base64");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException(KEY_ENVIRONMENT_VARIABLE + " must decode to "
                + KEY_BYTES + " bytes, got " + raw.length);
        }
        return raw;
    }
}
