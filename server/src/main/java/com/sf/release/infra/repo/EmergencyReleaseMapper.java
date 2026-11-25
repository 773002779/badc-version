package com.sf.release.infra.repo;

import com.sf.release.infra.repo.po.EmergencyReleasePO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for {@code emergency_release} (V32, P6-D design §2).
 *
 * <p><strong>Append-only.</strong> There is exactly one statement: the history is evidence, so the
 * application never updates or deletes a row. The read the pages use is the four columns on
 * {@code release_order}, not this table.
 */
@Mapper
public interface EmergencyReleaseMapper {

    /** Appends one occurrence; the service has already validated the reason and the actor. */
    @Insert("""
        INSERT INTO emergency_release (order_id, publish_no, reason, actor, created_at)
        VALUES (#{orderId}, #{publishNo}, #{reason}, #{actor}, #{createdAt})
        """)
    int insertEmergencyRelease(EmergencyReleasePO po);
}