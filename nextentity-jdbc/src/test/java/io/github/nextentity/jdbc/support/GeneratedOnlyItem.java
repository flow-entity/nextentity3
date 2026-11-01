package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 只有生成键列、没有任何可插入列的实体：`insert` 必须按方言渲染无列表 INSERT 并回填生成键
/// （H2/MySQL `insert into t () values ()`；PG/SQL Server `insert into t default values`）。
@Entity
@Table(name = "generated_only_item")
public class GeneratedOnlyItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public GeneratedOnlyItem() {}

    public Long id() {
        return id;
    }
}
