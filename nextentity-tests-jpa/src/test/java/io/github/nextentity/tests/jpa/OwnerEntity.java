package io.github.nextentity.tests.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 关联嵌套用例的父实体：只用于验证 join 路径解析与 LEFT JOIN 语义。
@Entity(name = "OwnerEntity")
@Table(name = "owner")
public class OwnerEntity {

    @Id
    private Long id;

    private String name;

    protected OwnerEntity() {}

    public OwnerEntity(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public OwnerEntity owner() {
        return this;
    }

    public Long idValue() {
        return id;
    }

    public String name() {
        return name;
    }
}
