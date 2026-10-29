package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.ReservedWord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 保留字标识符的 JDBC 侧实体：表名 `order`、列名 `desc` / `key` 都是数据库关键字。
///
/// 注解里的名字用反引号标记「必须引用」，meta 剥掉反引号后交方言渲染（MySQL 反引号、
/// H2/PG 双引号、SQL Server 方括号）；DDL 见 `schema-h2.sql` / `schema-mysql.sql` /
/// `schema-postgres.sql` / `schema-sqlserver.sql`。
@Entity
@Table(name = "`order`")
public class ReservedWordEntity implements ReservedWord {

    @Id
    private long id;

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
