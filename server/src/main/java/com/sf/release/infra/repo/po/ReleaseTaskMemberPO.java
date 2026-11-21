package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.task.MemberRole;
import com.sf.release.domain.task.TaskMember;

import java.time.Instant;

/**
 * Persistence object for {@code release_task_member}. The unique key
 * {@code (task_id, user_id)} makes a member a single row per task; {@code role} is stored as
 * its uppercase enum name.
 */
@TableName("release_task_member")
public class ReleaseTaskMemberPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private String userId;
    private String role;
    private Instant createdAt;
    private Instant updatedAt;

    public static ReleaseTaskMemberPO of(TaskMember member) {
        ReleaseTaskMemberPO po = new ReleaseTaskMemberPO();
        po.id = member.id();
        po.taskId = member.taskId();
        po.userId = member.userId();
        po.role = member.role().name();
        po.createdAt = member.createdAt();
        po.updatedAt = member.updatedAt();
        return po;
    }

    public TaskMember toDomain() {
        return new TaskMember(id, taskId, userId,
            role == null || role.isBlank() ? MemberRole.DEV : MemberRole.valueOf(role),
            createdAt, updatedAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}