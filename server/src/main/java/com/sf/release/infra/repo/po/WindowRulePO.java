package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.window.WindowRule;

import java.time.LocalTime;

/**
 * Persistence object for {@code window_rule} (V21): one recurring weekly slot of a release window.
 *
 * <p>{@code day_of_week} is the ISO weekday (1 = Monday .. 7 = Sunday) and the times are local
 * times of day. The column type {@code TIME} maps to {@link LocalTime} through MyBatis' built-in
 * handler; the pair is stored as-is, so a slot with {@code end_time < start_time} keeps its
 * cross-midnight meaning without a separate flag.
 */
@TableName("window_rule")
public class WindowRulePO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long windowId;
    private Integer dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;

    public static WindowRulePO of(long windowId, WindowRule rule) {
        WindowRulePO po = new WindowRulePO();
        po.windowId = windowId;
        po.dayOfWeek = rule.dayOfWeek();
        po.startTime = rule.start();
        po.endTime = rule.end();
        return po;
    }

    /**
     * The domain rule.
     *
     * @throws IllegalStateException a required column is NULL (the table declares none of them
     *                               nullable, so this is data corruption, not an empty value)
     */
    public WindowRule toDomain() {
        if (dayOfWeek == null || startTime == null || endTime == null) {
            throw new IllegalStateException(
                "window_rule " + id + " is incomplete: dayOfWeek/start/end are NOT NULL columns");
        }
        return new WindowRule(dayOfWeek, startTime, endTime);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getWindowId() {
        return windowId;
    }

    public void setWindowId(Long windowId) {
        this.windowId = windowId;
    }

    public Integer getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(Integer dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }
}