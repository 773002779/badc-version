package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.notification.NotificationChannel;
import com.sf.release.domain.notification.NotificationEventKey;
import com.sf.release.domain.notification.NotificationStatus;

import java.time.Instant;

/**
 * Persistence object for {@code notification} (V26).
 *
 * <p>The row is the unit of delivery: one event, one recipient, one channel. {@code dedupeKey} is
 * the unique key that makes repeated triggers harmless; {@code readAt} is only meaningful for the
 * in-app channel; {@code attempts}/{@code failureReason} belong to the WeCom delivery.
 */
@TableName("notification")
public class NotificationPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String eventKey;
    private String objectType;
    private String objectId;
    private String objectLabel;
    private String recipient;
    private String channel;
    private String title;
    private String body;
    private String status;
    private Instant readAt;
    private Integer attempts;
    private String failureReason;
    private String dedupeKey;
    private Instant createdAt;
    private Instant sentAt;

    /** The event of this row, or {@code null} when a newer build wrote an unknown key. */
    public NotificationEventKey event() {
        return NotificationEventKey.parse(eventKey);
    }

    public boolean read() {
        return readAt != null;
    }

    public NotificationChannel channelValue() {
        return channel == null || channel.isBlank()
            ? NotificationChannel.INAPP : NotificationChannel.valueOf(channel);
    }

    public NotificationStatus statusValue() {
        return status == null || status.isBlank()
            ? NotificationStatus.PENDING : NotificationStatus.valueOf(status);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public String getObjectId() {
        return objectId;
    }

    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }

    public String getObjectLabel() {
        return objectLabel;
    }

    public void setObjectLabel(String objectLabel) {
        this.objectLabel = objectLabel;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }
}