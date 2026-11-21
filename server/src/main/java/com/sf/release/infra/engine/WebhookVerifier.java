package com.sf.release.infra.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Authenticates a CODING webhook by an HMAC-SHA256 signature over the <strong>raw request
 * bytes</strong> (tech design §6.3).
 *
 * <p>The signature is compared against the shared secret configured as
 * {@code release.webhook.secret} and is supplied in the {@code X-Signature} header, either as
 * {@code <lowercase hex>} or as {@code sha256=<hex>}. Verification uses
 * {@link MessageDigest#isEqual} so a valid/invalid decision cannot be timed byte by byte.
 *
 * <p>Verification never parses JSON: the caller must hand in the exact bytes it read from the
 * request. The {@code payload_hash} stored in {@code engine_event_dedup} is the SHA-256 hex of
 * the same bytes, so a replayed delivery of a different payload under the same event id is
 * still deduplicated by id (the id is the idempotency key) and the hash documents the payload.
 *
 * <p><strong>Fail closed.</strong> Without a configured secret every callback is rejected with
 * 401 and the application still boots; the constructor only logs the missing configuration at
 * WARN. Secrets are never logged.
 */
@Component
public class WebhookVerifier {

    /** Header carrying the HMAC-SHA256 signature. */
    public static final String SIGNATURE_HEADER = "X-Signature";

    /** Optional algorithm prefix accepted in the header, e.g. {@code sha256=<hex>}. */
    static final String HMAC_PREFIX = "sha256=";

    private static final Logger log = LoggerFactory.getLogger(WebhookVerifier.class);
    private static final String ALGORITHM = "HmacSHA256";
    /** HMAC-SHA256 is 32 bytes, i.e. 64 hex characters. */
    private static final int HEX_LENGTH = 64;

    private final byte[] secret;

    public WebhookVerifier(@Value("${release.webhook.secret:}") String secret) {
        String configured = secret == null ? "" : secret.trim();
        if (configured.isEmpty()) {
            log.warn("release.webhook.secret is not configured: every CODING webhook is"
                + " rejected with 401 until the shared secret is provided");
            this.secret = new byte[0];
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * @param rawBody         exact request bytes as received
     * @param signatureHeader value of {@value #SIGNATURE_HEADER} (may be {@code null})
     * @return {@code true} only when the header is a well-formed hex HMAC of the raw body
     */
    public boolean verify(byte[] rawBody, String signatureHeader) {
        if (rawBody == null || secret.length == 0 || signatureHeader == null) {
            return false;
        }
        String candidate = signatureHeader.trim();
        if (candidate.isEmpty()) {
            return false;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (lower.startsWith(HMAC_PREFIX)) {
            candidate = candidate.substring(HMAC_PREFIX.length()).trim();
        }
        if (candidate.length() != HEX_LENGTH) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(candidate);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(provided, hmac(rawBody));
    }

    /** SHA-256 hex of the raw body, stored as {@code engine_event_dedup.payload_hash}. */
    public String payloadHash(byte[] rawBody) {
        if (rawBody == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawBody));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is not available");
        }
    }

    private byte[] hmac(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(rawBody);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available");
        }
    }
}