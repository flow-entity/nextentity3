package io.github.nextentity.jpa.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 测试用实体：`code` 只在父类 {@link BaseRecord} 上声明，本类只有一个 `@Id`。
///
/// 元模型会把 `code` 一并列进属性表，因此它同时验证两件事：Hibernate 认为继承属性属于本实体，
/// 而反射必须沿继承链才找得到对应字段。
@Entity
@Table(name = "derived_record")
public class DerivedRecord extends BaseRecord {

    @Id
    private Long id;

    public DerivedRecord() {}

    public DerivedRecord(Long id, String code) {
        this.id = id;
        setCode(code);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
