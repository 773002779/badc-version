package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * One received engine callback, used as the idempotency key of the webhook (tech design §4.3).
 * {@code event_id} is the primary key: the row is inserted before any side effect, so a
 * duplicate delivery fails the insert and is acknowledged without advancing anything.
 *
 * <p>{@code payloadHash} is the SHA-256 hex of the raw request body, stored for diagnosis. It is
 * deliberately not part of the identity: the vendor's event id is the idempotency key.
 */
@TableName("engine_event_dedup")
public class EngineEventDedupPO {

    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    private Instant receivedAt;
    private String payloadHash;

    public static EngineEventDedupPO of(String eventId, Instant receivedAt, String payloadHash) {
        EngineEventDedupPO po = new EngineEventDedupPO();
        po.eventId = eventId;
        po.receivedAt = receivedAt;
        po.payloadHash = payloadHash;
        return po;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }
}