package io.github.nextentity.jdbc.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 保留字表名 / 列名的查询实体（quoted 标识符）：`order` / `desc` / `key` 都用反引号标记为 quoted。
@Entity
@Table(name = "`order`")
public class QuotedOrder {

    @Id
    @Column(name = "`id`")
    private Long id;

    @Column(name = "`desc`")
    private String desc;

    @Column(name = "`key`")
    private Integer key;

    @ManyToOne
    @JoinColumn(name = "`order_id`", referencedColumnName = "`id`")
    private QuotedOwner owner;

    protected QuotedOrder() {}

    public Long id() {
        return id;
    }

    public String desc() {
        return desc;
    }

    public Integer key() {
        return key;
    }

    public QuotedOwner owner() {
        return owner;
    }
}
