package com.sf.release.infra.repo.po;

/**
 * One member of a release task joined with the display name from {@code app_user}. The join
 * is a {@code LEFT JOIN}: a member whose directory row is gone keeps its id and role and has
 * a {@code null} name.
 */
public class TaskMemberRow {

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