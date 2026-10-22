package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.util.ArrayList;
import java.util.List;

/// `Spec → Criteria` 的 JPA 编译器：一个节点一个 switch 分支，穷尽性由 sealed 接口保证。
///
/// 只做渲染：不查库、不做值转换（`@Convert` / 枚举 / 时间类型由 Hibernate 负责）、不判空值语义
/// （空集合已在 builder 折成 `Constant`，排序/窗口/锁的清理由 `QuerySpec.forCount()` / `forExists()` 完成）。
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
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<?> query =
                switch (spec.selection()) {
                    case EntitySelection entitySelection -> entityQuery(builder, spec, entitySelection);
                    case ValueSelection valueSelection -> valueQuery(builder, spec, valueSelection);
                };
        return new JpaQuery(query, spec);
    }

    @SuppressWarnings("unchecked")
    private CriteriaQuery<?> entityQuery(CriteriaBuilder builder, QuerySpec spec, EntitySelection selection) {
        Class<Object> type = (Class<Object>) spec.entityType();
        CriteriaQuery<Object> query = builder.createQuery(type);
        Root<Object> root = query.from(type);
        query.select(root);
        apply(new JpaCriteria(builder, root, meta), spec, query);
        return query;
    }

    private CriteriaQuery<?> valueQuery(CriteriaBuilder builder, QuerySpec spec, ValueSelection selection) {
        CriteriaQuery<Object[]> query = builder.createQuery(Object[].class);
        Root<?> root = query.from(spec.entityType());
        JpaCriteria criteria = new JpaCriteria(builder, root, meta);
        List<Selection<?>> items = new ArrayList<>(selection.items().size());
        for (Projection item : selection.items()) {
            items.add(criteria.projection(item));
        }
        query.select(builder.array(items));
        query.distinct(selection.distinct());
        apply(criteria, spec, query);
        return query;
    }

    private void apply(JpaCriteria criteria, QuerySpec spec, CriteriaQuery<?> query) {
        if (spec.where() != null) {
            query.where(criteria.predicate(spec.where()));
        }
        if (!spec.groupBy().isEmpty()) {
            List<Expression<?>> grouping = new ArrayList<>(spec.groupBy().size());
            for (AttributePath path : spec.groupBy()) {
                grouping.add(criteria.path(path));
            }
            query.groupBy(grouping);
        }
        if (!spec.orderBy().isEmpty()) {
            query.orderBy(orders(criteria, spec.orderBy()));
        }
    }

    private List<Order> orders(JpaCriteria criteria, List<Ordering> orderings) {
        List<Order> orders = new ArrayList<>(orderings.size());
        for (Ordering ordering : orderings) {
            Expression<?> path = criteria.path(ordering.path());
            orders.add(
                    switch (ordering.direction()) {
                        case ASC -> criteria.ascending(path);
                        case DESC -> criteria.descending(path);
                    });
        }
        return orders;
    }
}
