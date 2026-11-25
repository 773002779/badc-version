package com.sf.release.infra.repo.po;

/**
 * Read-only projection of one {@code release_task_member} row joined to its {@code app_user}
 * (P6-F design §2): the publish page shows the task's 测试人员 and 部署人员 by name.
 *
 * <p>The row keeps the stored role string so the application service decides which roles belong to
 * which column; {@code name} is null when the member's {@code app_user} row is gone (the join is a
 * {@code LEFT JOIN}, because a stale member row must still be listed, not silently dropped).
 *
 * <p>Column aliases are camelCase so the automatic mapping needs no result map. Not an aggregate
 * and no behaviour.
 */
public class ReleaseMemberRow {

    private String userId;
    private String name;
    private String role;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}