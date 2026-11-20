package com.sf.release.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sf.release.domain.task.ItemEvent;
import com.sf.release.domain.task.ItemStateMachine;
import com.sf.release.domain.task.ItemStatus;
import com.sf.release.domain.task.ReleaseItem;
import com.sf.release.infra.repo.ReleaseItemMapper;
import com.sf.release.infra.repo.po.ReleaseItemPO;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * One 变更项 main-state transition with the {@code row_version} CAS (tech design §3.2/§4.3),
 * shared by the pipeline event listener and the failure repairer so both replay exactly the
 * same rule.
 *
 * <p>No transaction boundary of its own: the caller decides the transaction (the listener runs
 * inside the orchestrator's transaction; the repairer wraps apply + resolve in one transaction).
 */
@Component
public class ItemTransitions {

    private final ReleaseItemMapper itemMapper;

    public ItemTransitions(ReleaseItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    /**
     * Applies {@code event} to the current state of {@code itemId}.
     *
     * @throws IllegalStateException when the item row does not exist
     * @throws RuntimeException      when the stored state cannot be read (for example a corrupt
     *                               status word); the caller records that as a repairable failure
     */
    public Outcome apply(long itemId, ItemEvent event) {
        ReleaseItemPO po = itemMapper.selectById(itemId);
        if (po == null) {
            throw new IllegalStateException("变更项不存在：" + itemId);
        }
        ReleaseItem item = po.toDomain();
        if (!ItemStateMachine.allowedEvents(item.status()).contains(event)) {
            return Outcome.NOT_ALLOWED;
        }
        ItemStatus next = ItemStateMachine.transit(item, event);
        int rows = itemMapper.update(null, Wrappers.<ReleaseItemPO>lambdaUpdate()
            .eq(ReleaseItemPO::getId, item.id())
            .eq(ReleaseItemPO::getRowVersion, item.rowVersion())
            .set(ReleaseItemPO::getStatus, next.name())
            .set(ReleaseItemPO::getRowVersion, item.rowVersion() + 1)
            .set(ReleaseItemPO::getUpdatedAt, Instant.now()));
        return rows == 1 ? Outcome.APPLIED : Outcome.CAS_LOST;
    }

    /** Result of one transition attempt. */
    public enum Outcome {
        /** The transition committed. */
        APPLIED,
        /** The state machine does not accept the event from the current state. */
        NOT_ALLOWED,
        /** A concurrent writer changed {@code row_version}; nothing was written. */
        CAS_LOST
    }
}