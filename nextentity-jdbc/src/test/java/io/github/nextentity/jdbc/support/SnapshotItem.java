package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 查询 SQL 快照的主实体：六个标量列覆盖 String / Integer / 枚举三种列类型。
@Entity
@Table(name = "item")
public class SnapshotItem {

    @Id
    private Long id;

    private String name;
    private Integer score;
    private Integer score2;
    private String note;

    @Enumerated(EnumType.STRING)
    private ItemStatus status;

    protected SnapshotItem() {}

    public SnapshotItem(long id, String name, Integer score, Integer score2, String note, ItemStatus status) {
        this.id = id;
        this.name = name;
        this.score = score;
        this.score2 = score2;
        this.note = note;
        this.status = status;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Integer score() {
        return score;
    }

    public Integer score2() {
        return score2;
    }

    public String note() {
        return note;
    }

    public ItemStatus status() {
        return status;
    }
}
