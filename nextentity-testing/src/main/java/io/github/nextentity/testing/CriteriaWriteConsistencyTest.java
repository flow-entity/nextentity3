package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.DeleteWhereStep;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.UpdateSetStep;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 条件 update / delete 的一致性：受影响行数由 {@link FixtureRow} 在内存里算，
/// 写入效果用后续查询读回验证（不是只看返回值）。
///
/// 后端接线两处：给一个"组装 step 并执行"的入口——JPA 的写入必须在事务里执行，
/// JDBC 可直接执行，这层差异收在子类里，基类保持后端中立。
///
/// @param <T> 后端实体类型
public abstract class CriteriaWriteConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 组装一条条件 update 并执行。
    ///
    /// @param work 在 step 上叠加 `set(...)` / `where(...)`
    /// @return 受影响行数
    protected abstract int runUpdate(Consumer<UpdateSetStep<T>> work);

    /// 组装一条条件 delete 并执行。
    ///
    /// @param work 在 step 上叠加 `where(...)`
    /// @return 受影响行数
    protected abstract int runDelete(Consumer<DeleteWhereStep<T>> work);

    @Test
    @DisplayName("条件 update：只改匹配行，返回受影响行数")
    void updateAffectsMatchingRowsOnly() {
        int affected = runUpdate(
                step -> step.set(Path.of(scoreRef()), 99).where(idRef()).eq(APPLE_ID));

        assertThat(affected).isEqualTo(1);
        assertThat(query().where(idRef()).eq(APPLE_ID).first().score()).isEqualTo(99);
        assertThat(query().where(idRef()).eq(BANANA_ID).first().score()).isEqualTo(20);
    }

    @Test
    @DisplayName("多行 update：受影响行数等于内存算出的匹配行数，匹配行全部改到")
    void updateAffectsEveryMatchingRow() {
        long expected = fixtureRows().stream()
                .filter(row -> row.status() == ItemStatus.NEW)
                .count();

        int affected = runUpdate(
                step -> step.set(Path.of(scoreRef()), 7).where(statusRef()).eq(ItemStatus.NEW));

        assertThat(affected).isEqualTo((int) expected);
        assertThat(query().where(statusRef()).eq(ItemStatus.NEW).list())
                .hasSize((int) expected)
                .allSatisfy(item -> assertThat(item.score()).isEqualTo(7));
    }

    @Test
    @DisplayName("不带 where 的 update 作用于全表")
    void updateWithoutWhereTouchesWholeTable() {
        int affected = runUpdate(step -> step.set(Path.of(scoreRef()), 1));

        assertThat(affected).isEqualTo(fixtureRows().size());
        assertThat(query().list()).allSatisfy(item -> assertThat(item.score()).isEqualTo(1));
    }

    @Test
    @DisplayName("条件 update 可以写 NULL")
    void updateCanWriteNull() {
        int affected = runUpdate(
                step -> step.set(Path.of(scoreRef()), null).where(idRef()).eq(CHERRY_ID));

        assertThat(affected).isEqualTo(1);
        assertThat(query().where(idRef()).eq(CHERRY_ID).first().score()).isNull();
    }

    @Test
    @DisplayName("没有匹配行时受影响行数是 0")
    void noMatchingRowsAffectNothing() {
        int updated = runUpdate(
                step -> step.set(Path.of(scoreRef()), 1).where(idRef()).eq(9_999L));
        int deleted = runDelete(step -> step.where(idRef()).eq(9_999L));

        assertThat(updated).isZero();
        assertThat(deleted).isZero();
        assertThat(query().list()).hasSize(fixtureRows().size());
    }

    @Test
    @DisplayName("条件 delete：只删匹配行，返回删除行数")
    void deleteRemovesMatchingRow() {
        int affected = runDelete(step -> step.where(idRef()).eq(BANANA_ID));

        assertThat(affected).isEqualTo(1);
        assertThat(query().where(idRef()).eq(BANANA_ID).list()).isEmpty();
        assertThat(query().list()).hasSize(fixtureRows().size() - 1);
    }

    @Test
    @DisplayName("不带 where 的 delete 清空全表")
    void deleteWithoutWhereEmptiesTable() {
        int affected = runDelete(step -> {});

        assertThat(affected).isEqualTo(fixtureRows().size());
        assertThat(query().list()).isEmpty();
    }
}
