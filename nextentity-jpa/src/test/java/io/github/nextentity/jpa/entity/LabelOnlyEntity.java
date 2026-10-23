package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/// 测试用**非法**实体：标了 `@Entity` 却没有任何 `@Id`。
///
/// 刻意**不**注册进 `JpaTestSupport` 的 `Configuration`：Hibernate 在启动期就会以
/// `No identifier specified for entity` 拒绝这种映射，注册了会让整个测试底座起不来。
/// 它只作为「元模型里没有 @Id 属性」这一情形的类型载体，由 `meta/JpaEntityTypeTest`
/// 用桩元模型驱动。
@Entity
@Table(name = "label_only")
public class LabelOnlyEntity {

    @Column(name = "label")
    private String label;

    public LabelOnlyEntity() {}

    public LabelOnlyEntity(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
