package io.github.nextentity.jpa.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/// 测试用实体：唯一的 `@ElementCollection` 属性（`labels`）。
///
/// 为什么必须有这个实体：`@ElementCollection` 是 `PluralAttribute`，但
/// `AbstractPluralAttribute.isAssociation()` 只对 `@OneToMany` / `@ManyToMany` 返回 `true`。
/// 于是 `meta/JpaMeta.java:52` 那个「PluralAttribute 提前返回 `true`」的分支**只有**靠
/// 集合值映射才能观测到：去掉它以后 `association(...)` 会落回 `isAssociation()` 返回 `false`，
/// 后果是 `compile/JpaPaths` 把它当普通列 `get()`（渲染出错），
/// `exec/JpaPersistExecutor` 把它当标量列 `update.set(...)`（写路径出错）。
///
/// 现有实体集里 `@OneToMany`（`VersionedLedger#entries`）与 `@ManyToMany`（`Article#tags`）
/// 的 `isAssociation()` 都是 `true`，删掉那个提前返回看不出差别——这条映射是刻意补上的反例。
@Entity
@Table(name = "element_collection_holder")
public class ElementCollectionHolder {

    @Id
    private Long id;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "element_collection_holder_labels", joinColumns = @JoinColumn(name = "holder_id"))
    @Column(name = "label")
    private List<String> labels = new ArrayList<>();

    public ElementCollectionHolder() {}

    public ElementCollectionHolder(Long id, List<String> labels) {
        this.id = id;
        this.labels = new ArrayList<>(labels);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels;
    }
}
