package io.github.nextentity.jdbc.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 保留字表名 / 列名的关联目标实体（Phase F）：注解名用反引号标记 → meta 记 quoted。
@Entity
@Table(name = "`owner`")
public class QuotedOwner {

    @Id
    @Column(name = "`id`")
    private Long id;

    @Column(name = "`name`")
    private String name;

    protected QuotedOwner() {}

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }
}
