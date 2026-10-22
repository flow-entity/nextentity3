package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// 一次编译期的 Criteria 渲染器：路径解析（含嵌套 join 缓存）、条件、投影。
///
/// 生命周期 = 一次 `JpaCompiler.compile(...)`。join 缓存挂在这里而不是挂到 {@link JpaMeta}：
/// `Join` 只对某个 `Root` / `CriteriaQuery` 有效，跨查询共享会拿到已失效的 Criteria 对象。
public final class JpaCriteria {

    private final CriteriaBuilder builder;
    private final Root<?> root;
    private final JpaMeta meta;

    /// 嵌套路径的 join 缓存：key 为路径前缀（`[]` 代表 root），同前缀只 join 一次。
    private final Map<List<String>, Path<?>> prefixes = new HashMap<>();

    public JpaCriteria(CriteriaBuilder builder, Root<?> root, JpaMeta meta) {
        this.builder = builder;
        this.root = root;
        this.meta = meta;
    }

    /// 把属性路径渲染成 Criteria 路径；中途的关联段按 `LEFT JOIN` 展开（对齐 2.2.3 外连接语义）。
    ///
    /// @param attributePath 属性路径
    /// @return Criteria 路径
    Path<?> path(AttributePath attributePath) {
        List<String> names = attributePath.names();
        return navigate(names.subList(0, names.size() - 1)).get(names.getLast());
    }

    /// 渲染条件节点。`Spec` 是 sealed，新增节点会让这里的 switch 编译失败。
    ///
    /// @param spec 条件节点
    /// @return Criteria 条件
    public Predicate predicate(Spec spec) {
        return switch (spec) {
            case Comparison comparison -> comparison(comparison);
            case Between between -> between(between);
            case In in -> in(in);
            case NullCheck nullCheck -> nullCheck(nullCheck);
            case Logical logical -> logical(logical);
            case Constant constant -> constant.value() ? builder.conjunction() : builder.disjunction();
        };
    }

    /// 渲染投影项。
    ///
    /// @param projection 投影项
    /// @return Criteria 选择项
    Selection<?> projection(Projection projection) {
        return switch (projection) {
            case PathProjection pathProjection -> path(pathProjection.path());
            case AggregateProjection aggregateProjection -> aggregate(aggregateProjection);
        };
    }

    /// 升序排序项。
    ///
    /// @param expression 排序表达式
    /// @return Criteria 排序项
    Order ascending(Expression<?> expression) {
        return builder.asc(expression);
    }

    /// 降序排序项。
    ///
    /// @param expression 排序表达式
    /// @return Criteria 排序项
    Order descending(Expression<?> expression) {
        return builder.desc(expression);
    }

    private Path<?> navigate(List<String> prefix) {
        if (prefix.isEmpty()) {
            return root;
        }
        List<String> key = List.copyOf(prefix);
        Path<?> cached = prefixes.get(key);
        if (cached != null) {
            return cached;
        }
        Path<?> parent = navigate(prefix.subList(0, prefix.size() - 1));
        String attribute = prefix.getLast();
        Path<?> resolved = meta.association(parent.getJavaType(), attribute)
                ? ((From<?, ?>) parent).join(attribute, JoinType.LEFT)
                : parent.get(attribute);
        prefixes.put(key, resolved);
        return resolved;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate comparison(Comparison comparison) {
        Path<?> path = path(comparison.path());
        Object value = comparison.value();
        return switch (comparison.operator()) {
            case EQ -> builder.equal(path, value);
            case NE -> builder.notEqual(path, value);
            case GT -> builder.greaterThan((Expression) path, (Comparable) value);
            case GE -> builder.greaterThanOrEqualTo((Expression) path, (Comparable) value);
            case LT -> builder.lessThan((Expression) path, (Comparable) value);
            case LE -> builder.lessThanOrEqualTo((Expression) path, (Comparable) value);
            case LIKE -> builder.like((Expression) path, (String) value);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate between(Between between) {
        Path<?> path = path(between.path());
        return builder.between((Expression) path, (Comparable) between.lower(), (Comparable) between.upper());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate in(In in) {
        CriteriaBuilder.In<Object> criteriaIn = builder.in((Expression) path(in.path()));
        for (Object value : in.values()) {
            criteriaIn.value(value);
        }
        return in.negated() ? criteriaIn.not() : criteriaIn;
    }

    private Predicate nullCheck(NullCheck nullCheck) {
        Path<?> path = path(nullCheck.path());
        return nullCheck.negated() ? builder.isNotNull(path) : builder.isNull(path);
    }

    private Predicate logical(Logical logical) {
        if (logical.operator() == LogicalOperator.NOT) {
            return predicate(logical.operands().getFirst()).not();
        }
        Predicate[] predicates =
                logical.operands().stream().map(this::predicate).toArray(Predicate[]::new);
        return logical.operator() == LogicalOperator.AND ? builder.and(predicates) : builder.or(predicates);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Selection<?> aggregate(AggregateProjection aggregateProjection) {
        Expression expression = (Expression) path(aggregateProjection.path());
        return switch (aggregateProjection.function()) {
            case MAX -> builder.greatest(expression);
            case SUM -> builder.sum(expression);
            case COUNT -> builder.count(expression);
            case COUNT_DISTINCT -> builder.countDistinct(expression);
        };
    }
}
