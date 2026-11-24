package com.sf.release.infra.repo;

import com.sf.release.infra.repo.po.DailyInstanceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Read-only SQL of the daily release pages (P5-A design §5). Like {@code ReleaseQueryMapper} it is
 * a separate mapper on purpose: the write mappers stay MyBatis-Plus {@code BaseMapper}s and this one
 * owns the few hand-written reads whose statement count is part of the contract.
 *
 * <p><strong>Scoped by {@code item_id}.</strong> A daily instance is found by its own
 * {@code item_id} column (V28), never through a release order — a daily release has no order. The
 * {@code exec_type = 'DAILY'} predicate keeps an accidental online row out even if it shared the
 * column, and the pipeline number of the detail is still globally unique.
 *
 * <p><strong>Query budget.</strong> One count plus one page statement for the records list; one
 * header statement plus the two shared node/task reads for the detail. No per-row follow-up.
 */
@Mapper
public interface DailyQueryMapper {

    /** Number of daily instances of one 变更项; the page total. */
    @Select("""
        SELECT COUNT(*)
        FROM pipeline_instance
        WHERE item_id = #{itemId} AND exec_type = 'DAILY'
        """)
    long countByItem(@Param("itemId") long itemId);

    /**
     * One page of the daily instances of {@code itemId}, newest first. {@code created_at} is the
     * business order; {@code id} breaks a same-millisecond tie deterministically.
     */
    @Select("""
        SELECT pi.id            AS instanceId,
               pi.item_id       AS itemId,
               pi.unit_id       AS unitId,
               pi.pipeline_no   AS pipelineNo,
               pi.status        AS status,
               pi.requested_by  AS requestedBy,
               pi.commit_sha    AS commitSha,
               pi.image_digest  AS imageDigest,
               pi.start_at      AS startAt,
               pi.end_at        AS endAt,
               pi.created_at    AS createdAt,
               i.source_branch  AS branch
        FROM pipeline_instance pi
        LEFT JOIN release_item i ON i.id = pi.item_id
        WHERE pi.item_id = #{itemId} AND pi.exec_type = 'DAILY'
        ORDER BY pi.created_at DESC, pi.id DESC
        LIMIT #{size} OFFSET #{offset}
        """)
    List<DailyInstanceRow> selectPage(@Param("itemId") long itemId,
                                      @Param("offset") long offset,
                                      @Param("size") int size);

    /** The daily instance with {@code pipelineNo}, or {@code null} when it is not a daily one. */
    @Select("""
        SELECT pi.id            AS instanceId,
               pi.item_id       AS itemId,
               pi.unit_id       AS unitId,
               pi.pipeline_no   AS pipelineNo,
               pi.status        AS status,
               pi.requested_by  AS requestedBy,
               pi.commit_sha    AS commitSha,
               pi.image_digest  AS imageDigest,
               pi.start_at      AS startAt,
               pi.end_at        AS endAt,
               pi.created_at    AS createdAt,
               i.source_branch  AS branch
        FROM pipeline_instance pi
        LEFT JOIN release_item i ON i.id = pi.item_id
        WHERE pi.pipeline_no = #{pipelineNo} AND pi.exec_type = 'DAILY'
        """)
    DailyInstanceRow selectByPipelineNo(@Param("pipelineNo") String pipelineNo);
}