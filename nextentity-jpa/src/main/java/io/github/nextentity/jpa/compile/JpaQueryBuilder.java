package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.util.ArrayList;
import java.util.List;

/// 按查询形态组装 Criteria 查询：实体查询用 `createQuery(实体)`，投影查询用
/// `createQuery(Object[].class)` + `select(cb.array(...))`。
///
/// 只做渲染：条件、排序、分组都转给 {@link JpaCriteria}，值转换留给 Hibernate。
final class JpaQueryBuilder {

    private final CriteriaBuilder builder;
    private final JpaMeta meta;

    JpaQueryBuilder(CriteriaBuilder builder, JpaMeta meta) {
        this.builder = builder;
        this.meta = meta;
    }

    @SuppressWarnings("unchecked")
    CriteriaQuery<?> entityQuery(QuerySpec spec) {
        Class<Object> type = (Class<Object>) spec.entityType();
        CriteriaQuery<Object> query = builder.createQuery(type);
        Root<Object> root = query.from(type);
        query.select(root);
        apply(new JpaCriteria(builder, root, meta), spec, query);
        return query;
    }

    CriteriaQuery<?> valueQuery(QuerySpec spec, ValueSelection selection) {
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
