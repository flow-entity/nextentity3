package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 关联快照的父表实体。
@Entity
@Table(name = "owner")
public class SnapshotOwner {

    @Id
    private Long id;

    private String name;

    protected SnapshotOwner() {}

    public SnapshotOwner(long id, String name) {
        this.id = id;
        this.name = name;
    }

    /// 未赋主键的关联对象：用于钉住「写外键前必须能取到关联 `@Id`」。
    public static SnapshotOwner unsaved() {
        return new SnapshotOwner();
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }
}
