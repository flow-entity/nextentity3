package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// `@JoinColumns(@JoinColumn(name = "owner_ref", updatable = false))`——可重复注解的**容器写法**，
/// 单列时与单列 `@JoinColumn` 完全同义。列名 `owner_ref` 刻意不等于默认命名 `owner_id`：
/// 容器写法被静默忽略时（`getAnnotation(JoinColumn.class)` 返回 null）会回落默认列名，用例因此可判红。
@Entity
@Table(name = "container_join_column_item")
public class ContainerJoinColumnItem {

    @Id
    private Long id;

    private String name;

    @ManyToOne
    @JoinColumns(@JoinColumn(name = "owner_ref", updatable = false))
    private SnapshotOwner owner;

    protected ContainerJoinColumnItem() {}

    public ContainerJoinColumnItem(long id, String name, SnapshotOwner owner) {
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
