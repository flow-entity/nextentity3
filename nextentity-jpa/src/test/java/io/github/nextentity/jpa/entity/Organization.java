package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 测试用组织实体：被 {@link Customer} 以二级关联引用，用于验证两级嵌套路径。
@Entity
@Table(name = "organization")
public class Organization {

    @Id
    private Long id;

    @Column(name = "name")
    private String name;

    public Organization() {}

    public Organization(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
