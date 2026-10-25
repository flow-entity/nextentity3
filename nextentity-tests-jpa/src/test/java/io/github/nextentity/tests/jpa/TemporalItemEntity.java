package io.github.nextentity.tests.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/// 时间类型的被查实体：`Instant` / `LocalDateTime` / `LocalDate` 三种列。
///
/// 此前非遗留用例里**没有任何**时间类型字段（`Instant` / `LocalDate` 零命中），时间维度因此
/// 完全没有证据面。三种类型的库列型各不相同（`timestamp` / `timestamp` / `date`），
/// 走的是三条不同的绑定路径：
/// - `Instant` 在 Hibernate 里按 `UTC` 归一化后落 `timestamp`，绑定时要再换算回本地时区；
/// - `LocalDateTime` 原样落 `timestamp`，不做时区换算；
/// - `LocalDate` 落 `date`，比 `timestamp` 少一整段精度。
///
/// 把它们放在同一个实体上，是为了让「换了一个字段的绑定路径」这类改动无法靠别的字段蒙混过关，
/// 同时省掉三套夹具。断言里的时间常量一律取**整秒**，不用 `now()`：库列精度（H2 与 MySQL 的
/// `timestamp` 小数位）与 JVM 时区都不该影响本用例要钉的语义。
@Entity(name = "TemporalItemEntity")
@Table(name = "temporal_item")
public class TemporalItemEntity {

    @Id
    private Long id;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "due_date")
    private LocalDate dueDate;

    private String name;

    protected TemporalItemEntity() {}

    public TemporalItemEntity(Long id, String name, Instant createdAt, LocalDateTime updatedAt, LocalDate dueDate) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.dueDate = dueDate;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public LocalDateTime updatedAt() {
        return updatedAt;
    }

    public LocalDate dueDate() {
        return dueDate;
    }
}
