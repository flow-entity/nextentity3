package io.github.nextentity.tests.jdbc;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 生成键用例的实体：`@GeneratedValue(IDENTITY)`，insert 时 id 不参与列清单，由 `getGeneratedKeys` 回填。
@Entity
@Table(name = "generated_item")
public class GeneratedIdItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected GeneratedIdItemEntity() {}

    public GeneratedIdItemEntity(String name) {
        this.name = name;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }
}
