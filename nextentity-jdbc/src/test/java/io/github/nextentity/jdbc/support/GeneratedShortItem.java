package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// IDENTITY + `Short` 主键：生成键按 id 字段类型窄化（`shortValueExact`，越界拒绝）。
@Entity
@Table(name = "short_generated_item")
public class GeneratedShortItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    private String name;

    protected GeneratedShortItem() {}

    public GeneratedShortItem(String name) {
        this.name = name;
    }

    public Short id() {
        return id;
    }

    public String name() {
        return name;
    }
}
