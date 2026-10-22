package io.github.nextentity.jpa.exec;

import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Selection;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jpa.compile.JpaCriteria;
import io.github.nextentity.jpa.compile.JpaQuery;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;

/// JPA 执行器：跑 {@link JpaQuery} 并按查询形态装配结果。
///
/// 三个方法对应 `Executor` 接缝；`first` / `single` / `slice` 由 core 的查询构建器用
/// `list` + `count` 组合，这里不重复实现。
public final class JpaExecutor implements Executor<JpaQuery> {

    private final EntityManager entityManager;
    private final JpaMeta meta;

    /// 创建执行器。
    ///
    /// @param entityManager 实体管理器
    /// @param meta          JPA 元模型
    public JpaExecutor(EntityManager entityManager, JpaMeta meta) {
        this.entityManager = entityManager;
        this.meta = meta;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> List<R> list(JpaQuery query) {
        QuerySpec spec = query.spec();
        TypedQuery<?> typedQuery = entityManager.createQuery(query.query());
        Window window = spec.window();
        if (window.offset() != null && window.offset() > 0) {
            typedQuery.setFirstResult(window.offset());
        }
        if (window.limit() != null && window.limit() > 0) {
            typedQuery.setMaxResults(window.limit());
        }
        if (spec.lock() != null) {
            // api.LockMode 与 jakarta LockModeType 同名闭集，名字映射即可，不写 5 分支 switch
            typedQuery.setLockMode(LockModeType.valueOf(spec.lock().name()));
        }
        return (List<R>) shape(typedQuery.getResultList(), spec.selection());
    }

    @Override
    public long count(JpaQuery query) {
        QuerySpec spec = query.spec();
        if (spec.aggregate()) {
            throw new UnsupportedOperationException(
                    "分组/聚合查询的 count 没有调用点，未实现（2.2.3 走子查询计数：" + spec.entityType().getName() + "）");
        }
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<?> root = countQuery.from(spec.entityType());
        countQuery.select(builder.count(builder.literal(true)));
        if (spec.where() != null) {
            countQuery.where(new JpaCriteria(builder, root, meta).predicate(spec.where()));
        }
        return entityManager.createQuery(countQuery).getSingleResult();
    }

    @Override
    public boolean exists(JpaQuery query) {
        QuerySpec spec = query.spec();
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Boolean> existsQuery = builder.createQuery(Boolean.class);
        Root<?> root = existsQuery.from(spec.entityType());
        existsQuery.select(builder.literal(true));
        if (spec.where() != null) {
            existsQuery.where(new JpaCriteria(builder, root, meta).predicate(spec.where()));
        }
        return !entityManager
                .createQuery(existsQuery)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    private static Object shape(List<?> rows, Selection selection) {
        return switch (selection) {
            case EntitySelection ignored -> rows;
            case ValueSelection values ->
                rows.stream().map(row -> shapeRow(row, values.items().size())).toList();
        };
    }

    private static Object shapeRow(Object row, int itemCount) {
        if (itemCount == 1) {
            return row instanceof Object[] array && array.length == 1 ? array[0] : row;
        }
        return row instanceof Object[] array ? array : new Object[] {row};
    }
}
