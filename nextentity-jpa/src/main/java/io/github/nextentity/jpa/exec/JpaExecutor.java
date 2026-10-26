package io.github.nextentity.jpa.exec;

import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.spec.QuerySpec;
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
    public JpaExecutor(EntityManager entityManager, JpaMeta meta) {
        this.entityManager = entityManager;
        this.meta = meta;
    }

    /// 执行列表查询，**原样交出** `TypedQuery` 的结果行。
    ///
    /// 行形态整形不在本层：单列把长度 1 的 `Object[]` 拆包、多列把非数组行包成数组，这套逻辑
    /// 属于 core 的 `RowAssembler` + `ValueMappers`（那里还知道投影了几列）。执行器的职责到
    /// 「拿到原始行」为止，否则同一套整形会有两份实现，改一处就必须同步另一处。
    ///
    /// 未实现入口只有一个：聚合/多列 `distinct` 规格的 `count()` 与分组/聚合规格的 `exists()`
    /// 会抛 `UnsupportedOperationException`，文案分别钉在 `JpaEndToEndTest` 的用例里。
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
        return (List<R>) typedQuery.getResultList();
    }

    @Override
    public long count(JpaQuery query) {
        QuerySpec spec = query.spec();
        if (spec.aggregate()) {
            throw new UnsupportedOperationException(
                    "分组/聚合规格的 count 未实现（2.2.3 走子查询计数：" + spec.entityType().getName() + "）");
        }
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<?> root = countQuery.from(spec.entityType());
        JpaCriteria criteria = new JpaCriteria(builder, root, meta);
        if (spec.selection() instanceof ValueSelection values && values.distinct()) {
            if (values.items().size() > 1) {
                // 多列去重计数的 SQL 没有统一形态（MySQL 是 COUNT(DISTINCT a, b)，PostgreSQL 要
                // COUNT(DISTINCT (a, b))），只数第一列会静默给出错数，所以显式拒绝
                throw new UnsupportedOperationException(
                        "多列 distinct 规格的 count 未实现（列数 " + values.items().size() + "，方言语义不一致）："
                                + spec.entityType().getName());
            }
            // 去重查询数的是「不同的投影值」
            countQuery.select(
                    builder.countDistinct(criteria.projection(values.items().getFirst())));
        } else {
            countQuery.select(builder.count(builder.literal(true)));
        }
        if (spec.where() != null) {
            countQuery.where(criteria.predicate(spec.where()));
        }
        return entityManager.createQuery(countQuery).getSingleResult();
    }

    /// 过滤后是否存在任何行。
    ///
    /// 只渲染 `where` + 取 1 行（2.2.3 `WhereImpl:134-148` 的 `SELECT_ANY` + limit 1）。
    /// **分组/聚合规格直接抛 `UnsupportedOperationException`**：`groupBy`/`having` 会改变
    /// "有没有行"的含义（HAVING 可以把所有组都过滤掉），只渲染 where 会给出与查询**相反**的
    /// 布尔值，因此不做静默近似——与 `count()` 的处理保持一致。
    @Override
    public boolean exists(JpaQuery query) {
        QuerySpec spec = query.spec();
        if (spec.aggregate()) {
            throw new UnsupportedOperationException(
                    "分组/聚合规格的 exists 未实现：" + spec.entityType().getName());
        }
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
}
