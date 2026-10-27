package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// IDENTITY + Integer 主键：生成键按 id 字段类型收窄（`Math.toIntExact`）。
@Entity
@Table(name = "integer_generated_item")
public class IntegerGeneratedItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String name;

    protected IntegerGeneratedItem() {}

    public IntegerGeneratedItem(String name) {
        this.name = name;
    }

    public Integer id() {
        return id;
    }

    public String name() {
        return name;
    }
}
