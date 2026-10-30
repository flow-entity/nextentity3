package io.github.nextentity.jpa.exec;

import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.CountScope;
import io.github.nextentity.core.spec.PathProjection;
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
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
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

    /// 计数：默认数**行**；`CountScope.GROUPS` 时数**组数**（`groupBy` 之后、`HAVING` 已过滤）。
    ///
    /// 组数的实现是「分组查询外面再包一层 `count(*)`」：标准 JPA Criteria 没有派生表 API，
    /// 只能经 Hibernate 的 `JpaCriteriaQuery#createCountQuery()`（见 {@link GroupedCount}），
    /// 一条 SQL、误区不降级成客户端数分组键。
    ///
    /// 「只有聚合投影 / 只有 `HAVING`」的形态仍显式拒绝：`forCounting()` 在没有 `groupBy` 时
    /// 不会给 `GROUPS` 口径，那种形态下「行数」语义不明确，宁可抛错也不静默给错数。
    @Override
    public long count(JpaQuery query) {
        QuerySpec spec = query.spec();
        if (spec.countScope() == CountScope.GROUPS) {
            return entityManager
                    .createQuery(GroupedCount.groupCount(query.query()))
                    .getSingleResult();
        }
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

    /// 过滤后是否存在任何行；`CountScope.GROUPS` 时判「是否存在任何组」（`HAVING` 已生效）。
    ///
    /// 行口径只渲染 `where` + 取 1 行（2.2.3 `WhereImpl:134-148` 的 `SELECT_ANY` + limit 1）。
    /// 组口径不能沿用「只拼 where」的近似：`groupBy`/`having` 会改变「有没有行」的含义
    /// （`HAVING` 可以把所有组都过滤掉），所以按原查询形态渲染 `select 1` + where + group by +
    /// having 再取 1 行——有行就是有组。**只有聚合投影 / 只有 `HAVING`**（没有 `groupBy`）的
    /// 形态仍显式拒绝，与 `count()` 保持一致。
    @Override
    public boolean exists(JpaQuery query) {
        QuerySpec spec = query.spec();
        if (spec.countScope() == CountScope.GROUPS) {
            return existsGrouped(spec);
        }
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

    /// 分组存在性：按原查询渲染（`select 1` + where + group by + having），限 1 行。
    ///
    /// 不建子查询：有行就是有组（`HAVING` 滤空的组不会返回行），与 `QuerySpec#forGroupExists`
    /// 的口径一致；限 1 行由 `forGroupExists` 的窗口表达，执行器只在结果集上取第 1 行。
    private boolean existsGrouped(QuerySpec spec) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Boolean> existsQuery = builder.createQuery(Boolean.class);
        Root<?> root = existsQuery.from(spec.entityType());
        JpaCriteria criteria = new JpaCriteria(builder, root, meta);
        existsQuery.select(builder.literal(true));
        if (spec.where() != null) {
            existsQuery.where(criteria.predicate(spec.where()));
        }
        List<Expression<?>> grouping = new ArrayList<>(spec.groupBy().size());
        for (AttributePath path : spec.groupBy()) {
            grouping.add(criteria.projection(new PathProjection(path)));
        }
        existsQuery.groupBy(grouping);
        if (spec.having() != null) {
            existsQuery.having(criteria.predicate(spec.having()));
        }
        return !entityManager
                .createQuery(existsQuery)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }
}
