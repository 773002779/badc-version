package com.sf.release.engine;

import com.sf.release.infra.crypto.CredentialCipher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 6 credential cipher: AES-GCM with a random 12-byte IV and a 128-bit tag, key from
 * the {@code CODING_CRED_KEY} environment variable (Base64, 32 bytes).
 *
 * <p>The ciphertext layout is {@code Base64(iv || ciphertext || tag)}. A missing or
 * malformed key must abort startup; the {@link ApplicationContextRunner} tests prove the
 * bean itself refuses to initialise instead of booting the whole application against a
 * broken configuration.
 */
class CredentialCipherTest {

    /** Base64 of the 32 ASCII bytes {@code 0123456789abcdef0123456789abcdef}. */
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final CredentialCipher cipher = new CredentialCipher(KEY);

    @Test
    void encrypt_decrypt_roundtrip_and_ciphertext_differs() {
        String a = cipher.encrypt("tok-123");
        String b = cipher.encrypt("tok-123");

        assertThat(a).isNotEqualTo(b);
        assertThat(cipher.decrypt(a)).isEqualTo("tok-123");
        assertThat(cipher.decrypt(b)).isEqualTo("tok-123");
    }

    @Test
    void ciphertext_is_base64_iv_and_gcm_tag_and_iv_is_random() {
        String token = "tok-123";
        byte[] first = Base64.getDecoder().decode(cipher.encrypt(token));
        byte[] second = Base64.getDecoder().decode(cipher.encrypt(token));

        int expectedLength = 12 + token.getBytes(StandardCharsets.UTF_8).length + 16;
        assertThat(first).hasSize(expectedLength);
        assertThat(second).hasSize(expectedLength);
        assertThat(slice(first, 0, 12))
            .as("12-byte IV must be fresh for every encryption")
            .isNotEqualTo(slice(second, 0, 12));
        assertThat(cipher.encrypt(token))
            .as("the Base64 string that reaches the database must not carry the plaintext")
            .doesNotContain(token);
    }

    @Test
    void tampered_ciphertext_is_rejected_without_leaking_material() {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("SUPER-SECRET"));
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("SUPER-SECRET")
            .hasMessageNotContaining(KEY);
    }

    @Test
    void missing_or_wrong_length_key_fails_fast() {
        assertThatThrownBy(() -> new CredentialCipher(null))
            .as("missing CODING_CRED_KEY")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CODING_CRED_KEY");
        assertThatThrownBy(() -> new CredentialCipher(""))
            .as("blank CODING_CRED_KEY")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CODING_CRED_KEY");
        assertThatThrownBy(() -> new CredentialCipher("not-base64!!"))
            .as("CODING_CRED_KEY must be Base64")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CODING_CRED_KEY");
        assertThatThrownBy(() -> new CredentialCipher(Base64.getEncoder().encodeToString(new byte[16])))
            .as("CODING_CRED_KEY must decode to 32 bytes")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32");
    }

    @Test
    void bean_refuses_to_start_without_a_valid_key() {
        assertThat(run(""))
            .isNotNull()
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .rootCause()
            .hasMessageContaining("CODING_CRED_KEY");

        assertThat(run(Base64.getEncoder().encodeToString(new byte[8])))
            .as("wrong length must fail the context as well")
            .isNotNull()
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .rootCause()
            .hasMessageContaining("32");

        ApplicationContextRunner valid = new ApplicationContextRunner()
            .withUserConfiguration(CipherConfiguration.class)
            .withPropertyValues("CODING_CRED_KEY=" + KEY);
        valid.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CredentialCipher.class)).isNotNull();
        });
    }

    private static Throwable run(String propertyValue) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CipherConfiguration.class)
            .withPropertyValues("CODING_CRED_KEY=" + propertyValue);
        ApplicationContextHolder holder = new ApplicationContextHolder();
        runner.run(context -> holder.failure = context.getStartupFailure());
        return holder.failure;
    }

    private static byte[] slice(byte[] source, int from, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(source, from, copy, 0, length);
        return copy;
    }

    @Configuration
    @Import(CredentialCipher.class)
    static class CipherConfiguration {
    }

    private static final class ApplicationContextHolder {
        private Throwable failure;
    }
}
