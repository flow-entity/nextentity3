package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaQuery;

/// `Spec → Criteria` 的 JPA 编译器：把规格按查询形态交给 {@link JpaQueryBuilder} 渲染。
///
/// 一个节点一个 switch 分支的渲染在 {@link JpaCriteria} 里，穷尽性由 sealed 接口保证；
/// 本类只负责「选形态 + 产出编译产物」。
public final class JpaCompiler implements Compiler<JpaQuery> {

    private final EntityManager entityManager;
    private final JpaMeta meta;

    /// 创建编译器。
    ///
    /// @param entityManager 实体管理器（一次请求一个）
    /// @param meta          JPA 元模型
    public JpaCompiler(EntityManager entityManager, JpaMeta meta) {
        this.entityManager = entityManager;
        this.meta = meta;
    }

    @Override
    public JpaQuery compile(QuerySpec spec) {
        JpaQueryBuilder builder = new JpaQueryBuilder(entityManager.getCriteriaBuilder(), meta);
        CriteriaQuery<?> query =
                switch (spec.selection()) {
                    case EntitySelection entitySelection -> builder.entityQuery(spec, entitySelection);
                    case ValueSelection valueSelection -> builder.valueQuery(spec, valueSelection);
                };
        return new JpaQuery(query, spec);
    }
}
