package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ReservedWord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 保留字标识符的 JPA 侧实体：表名 `order`、列名 `desc` / `key` 都是数据库关键字。
///
/// 注解用显式反引号标记引用；Hibernate 7 原生支持（H2 渲染双引号、MySQL 渲染反引号），
/// 与 JDBC 侧同一份注解形态对拍。
@Entity(name = "ReservedWordEntity")
@Table(name = "`order`")
public class ReservedWordEntity implements ReservedWord {

    @Id
    private Long id;

    private String name;

    @Column(name = "`desc`")
    private String desc;

    @Column(name = "`key`")
    private Integer key;

    protected ReservedWordEntity() {}

    public ReservedWordEntity(long id, String name, String desc, Integer key) {
        this.id = id;
        this.name = name;
        this.desc = desc;
        this.key = key;
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String desc() {
        return desc;
    }

    @Override
    public Integer key() {
        return key;
    }
}
