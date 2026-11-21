package com.sf.release.infra.repo.po;

/**
 * One {@code (task_id, status)} bucket of {@code release_item}, used by the task list to
 * aggregate {@code done/total/hasBlocking} for a whole page in one query.
 */
public class TaskStatusCountRow {

    private Long taskId;
    private String status;
    private Long cnt;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCnt() {
        return cnt;
    }

    public void setCnt(Long cnt) {
        this.cnt = cnt;
    }
}