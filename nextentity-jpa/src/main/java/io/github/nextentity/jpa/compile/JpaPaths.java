package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.FetchParent;
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
///
/// 另一份职责是 `fetch` 链（`LEFT JOIN FETCH`，消除 N+1）。JPA 的 `Fetch` 只继承 `FetchParent`
/// ——不是 `From`/`Path`——所以它不能当路径继续 `get`，也不能与谓词的 `join` 共用缓存，
/// 这里单独维护一份 fetch 前缀缓存。
final class JpaPaths {

    private final CriteriaBuilder builder;
    private final Root<?> root;
    private final JpaMeta meta;

    /// key 为路径前缀（`[]` 代表 root），同前缀只 join 一次。
    private final Map<List<String>, Path<?>> prefixes = new HashMap<>();

    /// fetch 链的前缀缓存：`Fetch` 不是 Path，进不了 {@link #prefixes}。
    private final Map<List<String>, FetchNode> fetches = new HashMap<>();

    /// fetch 链节点：父 fetch 节点 + 该节点解析出的 java 类型（下一段要按类型判关联）。
    private record FetchNode(FetchParent<?, ?> parent, Class<?> type) {}

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
        rejectPluralAssociation(parent.getJavaType(), attribute);
        return parent.get(attribute);
    }

    /// 注册一条 fetch 链（`LEFT JOIN FETCH`）：关联在**同一条 SQL** 里载入，消除 N+1。
    ///
    /// 只负责 select 侧预取：`where` / `order by` 命中同一路径时仍走 [#path] 的 join——
    /// to-one 关联 join 两次行数不变，只是 SQL 里多一个 join；把 fetch 的 join 复用给谓词
    /// 反而依赖「`Fetch` 也是 `From`」这种实现细节，标准 JPA 不保证。
    void fetch(AttributePath attributePath) {
        List<String> names = attributePath.names();
        FetchNode node = fetchNode(names.subList(0, names.size() - 1));
        fetchAssociation(node, names.getLast());
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
        rejectPluralAssociation(parent.getJavaType(), attribute);
        Path<?> resolved = meta.association(parent.getJavaType(), attribute)
                ? ((From<?, ?>) parent).join(attribute, JoinType.LEFT)
                : parent.get(attribute);
        prefixes.put(key, resolved);
        return resolved;
    }

    /// 解析 fetch 链的前缀：逐段 `LEFT JOIN FETCH`，同前缀只 fetch 一次（嵌套 fetch 复用父节点）。
    ///
    /// 集合关联与非关联段都拒绝：前者 join 出来是子行（`list()` 会重复），后者（标量 /
    /// `@Embedded`）本来就在同一张表里，没有可预取的实体。
    @SuppressWarnings({"unchecked", "rawtypes"})
    private FetchNode fetchNode(List<String> prefix) {
        if (prefix.isEmpty()) {
            return new FetchNode(root, root.getJavaType());
        }
        List<String> key = List.copyOf(prefix);
        FetchNode cached = fetches.get(key);
        if (cached != null) {
            return cached;
        }
        FetchNode parent = fetchNode(prefix.subList(0, prefix.size() - 1));
        Fetch fetched = fetchAssociation(parent, prefix.getLast());
        FetchNode node = new FetchNode(fetched, fetched.getAttribute().getJavaType());
        fetches.put(key, node);
        return node;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Fetch fetchAssociation(FetchNode node, String attribute) {
        Class<?> owner = node.type();
        if (!meta.association(owner, attribute)) {
            throw new IllegalArgumentException(
                    "fetch 只能指定关联属性（" + owner.getName() + "." + attribute + " 不是关联：标量 / 嵌入列本来就在同一张表里，没有可预取的实体）");
        }
        rejectPluralAssociation(owner, attribute);
        return node.parent().fetch(attribute, JoinType.LEFT);
    }

    /// 拒绝「路径段是集合关联」：`@OneToMany` / `@ManyToMany` 不能像单值关联那样 join，
    /// 一对多 join 按子行放大结果集——`list()` 出现重复实体，`count()` / `slice().total()`
    /// 数的是子行数，而 `EntityPath` 的 javadoc 承诺 `total` 是**实体行数**。
    ///
    /// 正确实现要走 `exists` 子查询或去重计数，目前没有；所以显式拒绝，而不是静默给错数
    /// （与 `count(聚合)`、多列 `distinct` 计数同一种处理）。
    private void rejectPluralAssociation(Class<?> javaType, String attribute) {
        if (meta.pluralAssociation(javaType, attribute)) {
            throw new UnsupportedOperationException("集合关联路径未支持（@OneToMany/@ManyToMany 需要子查询"
                    + "或去重计数语义，直接 join 会让 list 出现重复实体、count/total 放大）："
                    + javaType.getName() + "." + attribute);
        }
    }
}
