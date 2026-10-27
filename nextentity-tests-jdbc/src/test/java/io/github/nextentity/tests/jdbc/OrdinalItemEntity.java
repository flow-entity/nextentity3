package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.ItemStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// ORDINAL 存储分支的实体：库里是 `int` 序号，`meta` 按 `EnumStorage.ORDINAL` 转换。
@Entity
@Table(name = "ordinal_item")
public class OrdinalItemEntity {

    @Id
    private Long id;

    @Enumerated(EnumType.ORDINAL)
    private ItemStatus status;

    protected OrdinalItemEntity() {}

    public OrdinalItemEntity(long id, ItemStatus status) {
        this.id = id;
        this.status = status;
    }

    public Long id() {
        return id;
    }

    public ItemStatus status() {
        return status;
    }
}
