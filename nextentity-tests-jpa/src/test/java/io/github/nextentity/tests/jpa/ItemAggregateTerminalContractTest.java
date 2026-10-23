package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.ConsistencyTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 终止符在「聚合/分组规格」上的契约（后端执行器职责，不属于一致性基类）。
///
/// `having` / `groupBy` / 聚合投影会改变「有没有行」的含义：只把 `where` 拿去渲染 `COUNT(*)`
/// 会得到**相反**的结论（分组数 vs 总行数、having 过滤后的组数 vs 过滤前的行数），所以
/// `count()` 与 `exists()` 一律显式拒绝，抛 `UnsupportedOperationException`。
///
/// `slice()` 自己没有判断：它的 `total` 来自 `count()`，因此对聚合/分组规格是**级联**拒绝，
/// 抛的是 `count()` 的那个异常。
///
/// 拒绝消息里带着实体名（多列 `distinct` 还带列数），断言把这些片段一起钉住——消息写错、
/// 写漏信息同样会让用例变红。
///
/// 普通条件查询不受影响：`count()` 数满足条件的行，`exists()` 判断有没有满足条件的行。
///
/// 继承 `ConsistencyTestBase` 只为复用固定夹具（4 行），本类不含能力一致性断言。
class ItemAggregateTerminalContractTest extends ConsistencyTestBase<ItemEntity> {

    /// 拒绝消息里的实体名：`JpaExecutor` 用 `Class#getName()` 拼，因此是全限定名。
    private static final String ENTITY_NAME = ItemEntity.class.getName();

    private final JpaItemSupport support = new JpaItemSupport("item_aggregate_terminal");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 有 `having`、无 `groupBy`、无聚合投影：`count()` 必须显式拒绝，而不是给出一个会误导的数值。
    ///
    /// 守护 `nextentity-jpa` 的 `jpa/exec/JpaExecutor.java:57-59`。
    @Test
    void countRejectsHavingWithoutGroupByOrAggregateProjection() {
        var havingOnly = query().having(Path.of(idRef()).count().gt(3L));

        assertThatThrownBy(havingOnly::count)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("分组/聚合规格的 count 未实现")
                .hasMessageContaining(ENTITY_NAME);
    }

    /// 有 `groupBy`、无聚合投影：`count()` 走同一个拒绝分支，但判定入口是「`groupBy` 非空」，
    /// 与 having-only 形态各占一半，不能互相代替——漏掉这一半，`GROUP BY` 查询照常数行
    /// 就会被静默数成总行数。
    ///
    /// 守护 `nextentity-jpa` 的 `jpa/exec/JpaExecutor.java:57-59`（判定源在
    /// `nextentity-core` 的 `core/spec/QuerySpec.java:62`）。
    @Test
    void countRejectsGroupBy() {
        var groupedBy = query().groupBy(statusRef());

        assertThatThrownBy(groupedBy::count)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("分组/聚合规格的 count 未实现")
                .hasMessageContaining(ENTITY_NAME);
    }

    /// 多列 `distinct()` 的 `count()` 必须显式拒绝：`COUNT(DISTINCT a, b)` 在 MySQL 与 PostgreSQL
    /// 形态不同，只数第一列会静默给出错数。
    ///
    /// 守护 `nextentity-jpa` 的 `jpa/exec/JpaExecutor.java:66-72`：消息里带列数，列数算错就会红。
    @Test
    void countRejectsMultiColumnDistinct() {
        var multiColumnDistinct = query().distinct().select(Path.of(statusRef()), Path.of(noteRef()));

        assertThatThrownBy(multiColumnDistinct::count)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("多列 distinct 规格的 count 未实现")
                .hasMessageContaining("列数 2")
                .hasMessageContaining(ENTITY_NAME);
    }

    /// 聚合投影规格的 `exists()` 必须和 `count()` 一样显式拒绝：`HAVING` 能把所有组都过滤掉，
    /// 只渲染 `where` 会给出与查询**相反**的布尔值，静默近似比抛异常危险。
    ///
    /// 守护 `nextentity-jpa` 的 `jpa/exec/JpaExecutor.java:94-96`。
    @Test
    void existsRejectsAggregateSpec() {
        var aggregated = query().select(Path.of(idRef()).count());

        assertThatThrownBy(aggregated::exists)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("分组/聚合规格的 exists 未实现")
                .hasMessageContaining(ENTITY_NAME);
    }

    /// `slice()` 对聚合/分组规格的拒绝是**级联**，不是它自己的判断：`slice` 先取 `count()` 当总数
    /// （`nextentity-core` 的 `core/builder/QueryBuilder.java:236-242`，第一行就是 `long total = count();`），
    /// 于是 `count()` 的拒绝原样冒出来。
    ///
    /// 断言消息是 `count` 的那一条，正是「级联」的证据：若将来有人给 `slice` 加了自己的前置校验，
    /// 这条用例会红，迫使新分支也补上自己的契约用例。
    ///
    /// 守护 `nextentity-core` 的 `core/builder/QueryBuilder.java:237`，级联落点在
    /// `nextentity-jpa` 的 `jpa/exec/JpaExecutor.java:57-59`。
    @Test
    void sliceRejectsAggregateSpecViaCascade() {
        var aggregated = query().select(Path.of(idRef()).count());

        assertThatThrownBy(() -> aggregated.slice(0, 2))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("分组/聚合规格的 count 未实现")
                .hasMessageContaining(ENTITY_NAME);
    }

    /// 普通条件查询的 `count()` / `exists()` 语义不变（`score >= 20` → 2 行：banana 20、cherry 30）。
    @Test
    void plainConditionCountAndExistsUnchanged() {
        assertThat(query().where(scoreRef()).ge(20).count()).isEqualTo(2L);
        assertThat(query().where(scoreRef()).ge(20).exists()).isTrue();
        assertThat(query().where(scoreRef()).ge(999).exists()).isFalse();
    }
}
