package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// 属性路径 → Criteria 路径：中途的关联段按 `LEFT JOIN` 展开，并按路径前缀缓存 join。
///
/// 生命周期 = 一次编译。缓存挂在这里而不是挂到 {@link JpaMeta}：`Join` 只对某个 `Root` /
/// `CriteriaQuery` 有效，跨查询共享会拿到已失效的 Criteria 对象。
final class JpaPaths {

    private final CriteriaBuilder builder;
    private final Root<?> root;
    private final JpaMeta meta;

    /// key 为路径前缀（`[]` 代表 root），同前缀只 join 一次。
    private final Map<List<String>, Path<?>> prefixes = new HashMap<>();

    JpaPaths(CriteriaBuilder builder, Root<?> root, JpaMeta meta) {
        this.builder = builder;
        this.root = root;
        this.meta = meta;
    }

    /// 把属性路径渲染成 Criteria 路径；最后一段取 `get`，前面的关联段取 `join`。
    Path<?> path(AttributePath attributePath) {
        List<String> names = attributePath.names();
        Path<?> parent = navigate(names.subList(0, names.size() - 1));
        String attribute = names.getLast();
        rejectPluralAssociation(parent, attribute);
        return parent.get(attribute);
    }

    /// 升序排序项。
    Order ascending(Expression<?> expression) {
        return builder.asc(expression);
    }

    /// 降序排序项。
    Order descending(Expression<?> expression) {
        return builder.desc(expression);
    }

    /// 逐段解析路径，前缀结果按 `List<String>` 缓存（同一前缀只解析一次，也复用同一个 `join`）。
    ///
    /// 关联走 `join(LEFT)`、非关联（含 `@Embedded`）走 `get`。判定的**可观测性**有一处依赖：
    /// Hibernate 7 把嵌入属性的 `join` 摊平成同一张表的列，所以现在把这里换成恒 `join`
    /// 语句与结果都不变（`JpaEmbeddedChainTest` 实测）。但那是实现细节：若某个实现给嵌入
    /// `join` 加上 `is not null` 谓词，嵌入列全 null 的行会静默消失；
    /// `JpaEmbeddedChainTest#nullEmbeddedRowIsReturned` 钉住"该行必须返回"，换大版本或接
    /// 第二后端时由它先报。
    ///
    /// 集合关联（`@OneToMany` / `@ManyToMany`）在解析到的那一步就抛
    /// `UnsupportedOperationException`：它 join 出来是子行，会让 `list()` 重复、`count()` 放大。
    /// 最后一段由 [#path] 单独拒（那一段不走 `join`，走 `get`，但同样只能渲染成非法比较）。
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
        rejectPluralAssociation(parent, attribute);
        Path<?> resolved = meta.association(parent.getJavaType(), attribute)
                ? ((From<?, ?>) parent).join(attribute, JoinType.LEFT)
                : parent.get(attribute);
        prefixes.put(key, resolved);
        return resolved;
    }

    /// 拒绝「路径段是集合关联」：`@OneToMany` / `@ManyToMany` 不能像单值关联那样 join，
    /// 一对多 join 按子行放大结果集——`list()` 出现重复实体，`count()` / `slice().total()`
    /// 数的是子行数，而 `EntityPath` 的 javadoc 承诺 `total` 是**实体行数**。
    ///
    /// 正确实现要走 `exists` 子查询或去重计数，目前没有；所以显式拒绝，而不是静默给错数
    /// （与 `count(聚合)`、多列 `distinct` 计数同一种处理）。
    private void rejectPluralAssociation(Path<?> parent, String attribute) {
        if (meta.pluralAssociation(parent.getJavaType(), attribute)) {
            throw new UnsupportedOperationException("集合关联路径未支持（@OneToMany/@ManyToMany 需要子查询"
                    + "或去重计数语义，直接 join 会让 list 出现重复实体、count/total 放大）："
                    + parent.getJavaType().getName() + "." + attribute);
        }
    }
}
