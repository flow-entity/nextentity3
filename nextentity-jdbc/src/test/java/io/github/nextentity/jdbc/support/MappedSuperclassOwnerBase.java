package io.github.nextentity.jdbc.support;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/// 关联目标的继承基类：`@Id` 声明在 `@MappedSuperclass` 父类上，用于验证写关联取主键沿父类下钻。
@MappedSuperclass
public class MappedSuperclassOwnerBase {

    @Id
    private Long id;

    protected MappedSuperclassOwnerBase() {}

    protected MappedSuperclassOwnerBase(Long id) {
        this.id = id;
    }

    public Long id() {
        return id;
    }
}
