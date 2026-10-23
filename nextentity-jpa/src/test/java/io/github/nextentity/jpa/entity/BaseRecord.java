package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/// 测试用父类：把 `code` 声明在 `@MappedSuperclass` 上，子实体自己没有这个字段。
///
/// 用途是元数据解析的**继承链分支**：`JpaEntityAttribute.resolveField` 在子类上
/// `getDeclaredField` 失败后必须继续往父类找，否则继承映射的实体一个属性都读不到。
@MappedSuperclass
public abstract class BaseRecord {

    @Column(name = "code")
    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
