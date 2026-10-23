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
/// `having` / `groupBy` 会改变「有没有行」的含义：只把 `where` 拿去渲染 `COUNT(*)` 会得到**相反**的结论
/// （分组数 vs 总行数、having 过滤后的组数 vs 过滤前的行数），所以 `count()` 一律拒绝，抛
/// `UnsupportedOperationException`；`exists()` 的同一半由 `jpa/JpaEndToEndTest` 覆盖，这里不重复造。
///
/// 普通条件查询不受影响：`count()` 数满足条件的行，`exists()` 判断有没有满足条件的行。
///
/// 继承 `ConsistencyTestBase` 只为复用固定夹具（4 行），本类不含能力一致性断言。
class ItemAggregateTerminalContractTest extends ConsistencyTestBase<ItemEntity> {

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
    @Test
    void countRejectsHavingWithoutGroupByOrAggregateProjection() {
        var havingOnly = query().having(Path.of(idRef()).count().gt(3L));

        assertThatThrownBy(havingOnly::count).isInstanceOf(UnsupportedOperationException.class);
    }

    /// 普通条件查询的 `count()` / `exists()` 语义不变（`score >= 20` → 2 行：banana 20、cherry 30）。
    @Test
    void plainConditionCountAndExistsUnchanged() {
        assertThat(query().where(scoreRef()).ge(20).count()).isEqualTo(2L);
        assertThat(query().where(scoreRef()).ge(20).exists()).isTrue();
        assertThat(query().where(scoreRef()).ge(999).exists()).isFalse();
    }
}
