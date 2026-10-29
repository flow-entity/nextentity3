package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 关联快照的子表实体：`@ManyToOne` 用于 join 渲染与关联写入拒绝。
@Entity
@Table(name = "asset")
public class SnapshotAsset {

    @Id
    private Long id;

    private String name;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private SnapshotOwner owner;

    protected SnapshotAsset() {}

    public SnapshotAsset(long id, String name, SnapshotOwner owner) {
        this.id = id;
        this.name = name;
        this.owner = owner;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public SnapshotOwner owner() {
        return owner;
    }
}
