package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.entity.Article;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.entity.VersionedLedger;
import io.github.nextentity.jpa.exec.JpaExecutor;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 集合关联路径（`@OneToMany` / `@ManyToMany`）的**拒绝语义**：`JpaPaths#rejectPluralAssociation`
/// 的两处调用点都要抛 `UnsupportedOperationException`，而单值关联必须照常 join。
///
/// 为什么是拒绝而不是 join：一对多 join 按子行放大结果集——`list()` 出现重复实体，
/// `count()` / `slice().total()` 数的是子行数，而 `EntityPath` 的 javadoc 承诺 `total`
/// 是**实体行数**。正确实现要走 `exists` 子查询或去重计数，目前没有；静默 join 会给出一个
/// "看起来对"的错误数，那是本用例要挡住的那类缺陷。
///
/// 两处入口分别覆盖：
/// - 中段（`["tags", "label"]`）：`navigate` 在 `join` 之前拒绝；
/// - 末段（`["entries"]`）：`path` 在 `get` 之前拒绝——这一段不 join，但集合路径只可能渲染成
///   非法比较（`entries is null` 之类），同样不许发出去。
///
/// 第三条用例是**反向控制**：`@ManyToOne` 的 `customer` 必须照常 join。判据若被写成恒真
/// （把 `association` 换成 `pluralAssociation`、或让它恒真），前两条照样绿，而所有嵌套路径
/// 查询会集体失效——反向控制是这类"过度拒绝"的唯一探测器。
class JpaPluralAssociationRejectionTest extends JpaTestSupport {

    /// `ShopOrder` 的整句形态，用于反向控制：单值关联照常 `left join`。
    private static final String SHOP_ORDER_JOINED = "select so1_0.id,so1_0.amount,so1_0.customer_id,so1_0.note,"
            + "so1_0.status,so1_0.version from shop_order so1_0"
            + " left join customer c1_0 on c1_0.id=so1_0.customer_id where c1_0.name=?";

    @Test
    @DisplayName("中段是集合关联：编译期拒绝，而不是 join 出子行")
    void pluralAssociationInMiddleSegmentIsRejected() {
        // Article.tags 是 @ManyToMany（连接表 article_tag）：join 出来每个标签一行
        AttributePath label = AttributePath.of(Article.class, "tags", "label");

        assertThatThrownBy(() -> renderSql(Article.class, label))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("集合关联路径未支持")
                .hasMessageContaining(Article.class.getName() + ".tags");
    }

    @Test
    @DisplayName("末段是集合关联：同样拒绝，不把集合路径渲染成比较")
    void pluralAssociationAsLastSegmentIsRejected() {
        // VersionedLedger.entries 是 @OneToMany（子表持外键）
        AttributePath entries = AttributePath.of(VersionedLedger.class, "entries");

        assertThatThrownBy(() -> renderSql(VersionedLedger.class, entries))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("集合关联路径未支持")
                .hasMessageContaining(VersionedLedger.class.getName() + ".entries");
    }

    @Test
    @DisplayName("单值关联（@ManyToOne）照常 join")
    void singleValuedAssociationStillJoins() {
        AttributePath name = AttributePath.of(ShopOrder.class, "customer", "name");

        assertThat(renderSql(ShopOrder.class, name)).containsExactly(SHOP_ORDER_JOINED);
    }

    /// 公开入口同样拒绝：`EntityPath.of(Article::getTags)` 是合法签名
    /// （`PathRef<Article, List<Tag>>`），调用方不必知道底层是集合属性就能构造出来。
    @Test
    @DisplayName("EntityPath.of(集合属性)：公开入口同样拒绝")
    void pluralAssociationThroughPublicPathIsRejected() {
        assertThatThrownBy(() -> inTx(entityManager -> operations(entityManager, Article.class)
                        .query()
                        .where(EntityPath.of(Article::getTags))
                        .isNull()
                        .list()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("集合关联路径未支持");
    }

    /// 按原始 `QuerySpec` 跑一次并返回归一化后的语句；拒绝时异常直接抛出。
    private List<String> renderSql(Class<?> entityType, AttributePath path) {
        return inTx(entityManager -> {
            QuerySpec spec = new QuerySpec(
                    entityType,
                    new EntitySelection(),
                    new Comparison(path, ComparisonOperator.EQ, Literal.of("Alice")),
                    List.of(),
                    null,
                    List.of(),
                    Window.NONE,
                    null);
            new JpaExecutor(entityManager, meta()).list(new JpaCompiler(entityManager, meta()).compile(spec));
            return normalized(drainSql());
        });
    }

    private <T> EntityOperations<T> operations(EntityManager entityManager, Class<T> type) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory()).operations(type);
    }
}
