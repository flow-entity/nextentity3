package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.Item;
import io.github.nextentity.testing.ItemStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 一致性用例的 JDBC 侧实体：实现 `nextentity-testing` 的夹具接口，形状与固定夹具一致。
///
/// 与 `nextentity-tests-jpa/ItemEntity` 字段一一对应，但**不带任何 Hibernate 注解**——
/// JDBC 后端的 `JdbcMeta` 只认 `jakarta.persistence` 注解，测试实体就是它的输入。
@Entity
@Table(name = "item")
public class ItemEntity implements Item {

    @Id
    private Long id;

    private String name;

    private Integer score;

    private Integer score2;

    private String note;

    @Enumerated(EnumType.STRING)
    private ItemStatus status;

    protected ItemEntity() {}

    public ItemEntity(long id, String name, Integer score, Integer score2, String note, ItemStatus status) {
        this.id = id;
        this.name = name;
        this.score = score;
        this.score2 = score2;
        this.note = note;
        this.status = status;
    }

    @Override
    public Long id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Integer score() {
        return score;
    }

    @Override
    public Integer score2() {
        return score2;
    }

    @Override
    public String note() {
        return note;
    }

    @Override
    public ItemStatus status() {
        return status;
    }
}
