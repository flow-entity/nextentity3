package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 单列 `@JoinColumn(name = "owner_ref", updatable = false)` 的**对照组**：与
/// [ContainerJoinColumnItem] 的容器写法必须得到同一套列名 / 只读标志 / 写语句。
@Entity
@Table(name = "singular_join_column_item")
public class SingularJoinColumnItem {

    @Id
    private Long id;

    private String name;

    @ManyToOne
    @JoinColumn(name = "owner_ref", updatable = false)
    private SnapshotOwner owner;

    protected SingularJoinColumnItem() {}

    public SingularJoinColumnItem(long id, String name, SnapshotOwner owner) {
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
