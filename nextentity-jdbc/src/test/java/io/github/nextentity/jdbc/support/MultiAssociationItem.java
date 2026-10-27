package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 两个 `@ManyToOne` 指向同一张表：用于验证「不同路径各注册一个 join、别名按出现顺序编号」。
@Entity
@Table(name = "multi_association_item")
public class MultiAssociationItem {

    @Id
    private Long id;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private SnapshotOwner owner;

    @ManyToOne
    @JoinColumn(name = "creator_id")
    private SnapshotOwner creator;

    protected MultiAssociationItem() {}
}
