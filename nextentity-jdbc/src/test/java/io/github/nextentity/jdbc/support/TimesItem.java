package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/// 时间列类型样本：`java.time` 与 `java.sql` 两组并存，用于 SQL 快照（写侧转换）与建表往返（读侧还原）。
@SuppressWarnings({"PMD.TooManyMethods", "PMD.ExcessiveParameterList"})
@Entity
@Table(name = "times_item")
public class TimesItem {

    @Id
    private Long id;

    private LocalDate birthDate;
    private LocalDateTime createdAt;
    private LocalTime wakeTime;
    private Instant syncedAt;
    private Date sqlDate;
    private Time sqlTime;
    private Timestamp sqlTimestamp;

    protected TimesItem() {}

    public TimesItem(
            long id,
            LocalDate birthDate,
            LocalDateTime createdAt,
            LocalTime wakeTime,
            Instant syncedAt,
            Date sqlDate,
            Time sqlTime,
            Timestamp sqlTimestamp) {
        this.id = id;
        this.birthDate = birthDate;
        this.createdAt = createdAt;
        this.wakeTime = wakeTime;
        this.syncedAt = syncedAt;
        this.sqlDate = sqlDate;
        this.sqlTime = sqlTime;
        this.sqlTimestamp = sqlTimestamp;
    }

    public Long id() {
        return id;
    }

    public LocalDate birthDate() {
        return birthDate;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalTime wakeTime() {
        return wakeTime;
    }

    public Instant syncedAt() {
        return syncedAt;
    }

    public Date sqlDate() {
        return sqlDate;
    }

    public Time sqlTime() {
        return sqlTime;
    }

    public Timestamp sqlTimestamp() {
        return sqlTimestamp;
    }
}
