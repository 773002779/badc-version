package com.sf.release.web.controller;

import com.sf.release.application.CodingWebhookAppService;
import com.sf.release.application.CodingWebhookAppService.WebhookResult;
import com.sf.release.common.ApiResponse;
import com.sf.release.common.BizError;
import com.sf.release.common.security.Public;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Machine-to-machine entry point of the CODING callback (tech design §6.3).
 *
 * <p>{@link Public} skips the platform identity header: this endpoint authenticates itself by
 * verifying the HMAC-SHA256 signature over the <strong>raw body</strong> in
 * {@link CodingWebhookAppService}. The body is read as {@code byte[]} and never as a mapped
 * object, so the signature is computed over exactly the bytes the vendor signed.
 *
 * <p>HTTP semantics: {@code 200} for an accepted, duplicated or ignored (unknown task/status)
 * delivery — the vendor should not retry those; {@code 401} for a missing/invalid signature
 * (nothing is written); {@code 400} for a signed but unparseable payload.
 */
@RestController
@RequestMapping("/api/v1/hooks")
public class CodingWebhookController {

    private final CodingWebhookAppService webhookService;

    public CodingWebhookController(CodingWebhookAppService webhookService) {
        this.webhookService = webhookService;
    }

    @Public
    @PostMapping("/coding")
    public ResponseEntity<?> coding(
            @RequestHeader(name = CodingWebhookAppService.SIGNATURE_HEADER, required = false)
            String signature,
            @RequestBody(required = false) byte[] rawBody) {
        WebhookResult result = webhookService.receive(rawBody, signature);
        return switch (result.outcome()) {
            case INVALID_SIGNATURE -> ResponseEntity.status(401)
                .body(ApiResponse.fail(BizError.FORBIDDEN, "webhook 签名校验失败", null));
            case MALFORMED -> ResponseEntity.badRequest()
                .body(ApiResponse.fail(BizError.PARAM_INVALID, result.detail(),
                    Map.of("eventId", orEmpty(result.eventId()))));
            case ACCEPTED, DUPLICATE, IGNORED -> ResponseEntity.ok(ApiResponse.ok(result));
        };
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}