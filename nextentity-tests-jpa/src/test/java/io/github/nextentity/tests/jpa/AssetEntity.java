package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.Asset;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 关联嵌套用例的被查实体：`owner` 是 `@ManyToOne`（point-mall 的 17 处关联全是 `@ManyToOne`，无 `@OneToMany`）。
@Entity(name = "AssetEntity")
@Table(name = "asset")
public class AssetEntity implements Asset {

    @Id
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private OwnerEntity owner;

    protected AssetEntity() {}

    public AssetEntity(long id, String name, OwnerEntity owner) {
        this.id = id;
        this.name = name;
        this.owner = owner;
    }

    /// 嵌套路径的起点：`EntityPath.of(AssetEntity::owner)` 会从它解析属性名 `owner`。
    public OwnerEntity owner() {
        return owner;
    }

    @Override
    public Long id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }
}
