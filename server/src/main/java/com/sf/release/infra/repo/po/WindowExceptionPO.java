package com.sf.release.infra.repo.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sf.release.domain.window.WindowException;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Persistence object for {@code window_exception} (V21): a date exception of a release window.
 *
 * <p>{@code mode} is the {@link WindowException.Mode} name; {@code start_time}/{@code end_time} are
 * only meaningful for {@code OPEN} and are stored as NULL for a closed date or an open whole day.
 */
@TableName("window_exception")
public class WindowExceptionPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long windowId;
    private LocalDate theDate;
    private String mode;
    private LocalTime startTime;
    private LocalTime endTime;

    public static WindowExceptionPO of(long windowId, WindowException exception) {
        WindowExceptionPO po = new WindowExceptionPO();
        po.windowId = windowId;
        po.theDate = exception.date();
        po.mode = exception.mode().name();
        po.startTime = exception.start();
        po.endTime = exception.end();
        return po;
    }

    /**
     * The domain exception. A missing mode falls back to {@code CLOSED}, which is the
     * <em>fail-closed</em> direction (the date closes rather than silently opening); every other
     * required column is NOT NULL in the table.
     */
    public WindowException toDomain() {
        if (theDate == null) {
            throw new IllegalStateException("window_exception " + id + " has no the_date");
        }
        return new WindowException(
            theDate,
            mode == null || mode.isBlank()
                ? WindowException.Mode.CLOSED : WindowException.Mode.valueOf(mode),
            startTime,
            endTime);
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

    public LocalDate getTheDate() {
        return theDate;
    }

    public void setTheDate(LocalDate theDate) {
        this.theDate = theDate;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
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