package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 生成键-only + **quoted 表名**的实体：钉住无列表 INSERT 分支里的 `identifier(table, isTableQuoted, dialect)`
/// 仍按 quoted 渲染（删掉 `isTableQuoted()` 实参只在带引号的表上可观察，未引号的夹具杀不死该变异体）。
@Entity
@Table(name = "`generated_only_item`")
public class QuotedGeneratedOnlyItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public QuotedGeneratedOnlyItem() {}

    public Long id() {
        return id;
    }
}
