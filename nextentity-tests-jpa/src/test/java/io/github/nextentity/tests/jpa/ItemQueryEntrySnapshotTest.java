package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Expression;
import io.github.nextentity.api.LockMode;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.ProjectionMappers;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.ConsistencyTestBase;
import io.github.nextentity.testing.ItemStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 查询入口（`select` / `project` / `lock`）下发语句的**整句快照**：把「六列投影」与
/// 「一次查询只有一份投影」两类语义逐字钉住。
///
/// 与一致性差分用例（`ProjectionDifferentialTest` 的六列格、`ProjectionReplacementDifferentialTest`）
/// 分工不同：那里证明**结果值**对，这里证明**语句形状**对。两者的交集正是这一族最典型的缺陷——
/// 列序被打乱时结果值也会错（差分能抓），但「两份投影被拼接而不是替换」在结果值上可能只是列数变多、
/// 强转恰好通过，整句快照一眼就能看出来（`select a,b,c,d` 而不是 `select c,d`）。
///
/// 快照只对 H2 + 当前 Hibernate 版本成立（换方言 `limit`/`fetch first`/`for update` 的渲染就不同），
/// 所以不接 MySQL 通道，也不放进 `nextentity-testing` 的后端中立基类。
///
/// 归一化只做两件不影响语义的事：转小写、把连续空白压成一个空格——格式调整不会误报，
/// 而 token 顺序、括号、占位符个数、列序列全部在断言范围内。
class ItemQueryEntrySnapshotTest extends ConsistencyTestBase<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_query_entry_snapshot");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }

    @Test
    void snapshotsSixColumnProjectionsAndProjectionReplacement() {
        List<String> actual = new ArrayList<>();

        // 1. 六列投影（`ProjectionMappers.select` 的 `Tuple6` 路径）：c0..c5 全序列，列序即实参顺序
        actual.add(sqlOf(() -> query().project(ProjectionMappers.select(
                        Path.of(idRef()),
                        Path.of(nameRef()),
                        Path.of(scoreRef()),
                        Path.of(score2Ref()),
                        Path.of(noteRef()),
                        Path.of(statusRef())))
                .orderBy(idRef())
                .asc()
                .list()));

        // 2. 六列自定义映射器：`expressions()` 列序与上一条相同，渲染出的整句必须一致
        actual.add(sqlOf(
                () -> query().project(new DetailMapper()).orderBy(idRef()).asc().list()));

        // 3. select(a, b) 之后又 select(c, d)：只出后两列；拼接的话这里会出现四列
        actual.add(sqlOf(() -> {
            EntityQuery<ItemEntity> entry = query();
            entry.select(idRef(), nameRef());
            entry.select(scoreRef(), score2Ref()).orderBy(idRef()).asc().list();
        }));

        // 4. select 之后又 project：按映射器的三列走（不是 select 的两列，也不是五列拼接）
        actual.add(sqlOf(() -> {
            EntityQuery<ItemEntity> entry = query();
            entry.select(idRef(), nameRef());
            entry.project(new BriefMapper()).orderBy(idRef()).asc().list();
        }));

        // 5. project 之后又 select：按 select 的两列走（映射器的三列被替换掉）
        actual.add(sqlOf(() -> {
            EntityQuery<ItemEntity> entry = query();
            entry.project(new BriefMapper());
            entry.select(idRef(), scoreRef()).orderBy(idRef()).asc().list();
        }));

        // 6. 悲观锁单行查询（USAGE §9 的示例形态）：`lock(...).single()` 的整句骨架——
        //    `for update` 必须在语句末尾、`fetch first ? rows only` 在它之前
        actual.add(sqlOf(() -> support.inTransaction(() -> query().where(idRef())
                .eq(APPLE_ID)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .single())));

        assertThat(actual)
                .containsExactly(
                        "select ie1_0.id,ie1_0.name,ie1_0.score,ie1_0.score2,ie1_0.note,ie1_0.status from item ie1_0 order by 1",
                        "select ie1_0.id,ie1_0.name,ie1_0.score,ie1_0.score2,ie1_0.note,ie1_0.status from item ie1_0 order by 1",
                        "select ie1_0.score,ie1_0.score2 from item ie1_0 order by ie1_0.id",
                        "select ie1_0.id,ie1_0.score,ie1_0.status from item ie1_0 order by 1",
                        "select ie1_0.id,ie1_0.score from item ie1_0 order by 1",
                        "select ie1_0.id,ie1_0.name,ie1_0.note,ie1_0.score,ie1_0.score2,ie1_0.status from item ie1_0 where ie1_0.id=? order by ie1_0.id fetch first ? rows only for update");
    }

    /// 悲观锁单行查询的**结果**断言——补上面那条快照抓不到的一半。
    ///
    /// 快照只证明语句文本对：`where ie1_0.id = ? ... fetch first ? rows only`。文本里的两个
    /// `?` 一个是主键绑定（Long），一个是 `setMaxResults` 交给驱动的行数上限——**绑定值
    /// `StatementInspector` 原理上拿不到**，所以"where 里的 id 被绑成了别的值"这种错，
    /// 快照一个字都不会变（例如绑成 `BANANA_ID`，语句仍逐字节相同）。
    ///
    /// 这一格用结果兜：取回的行必须就是 `where` 里那个 id。主键特意取 `CHERRY_ID`（3）而不是
    /// `APPLE_ID`（1）——`single()` 的取行上限是 2，若 id 绑错成上限值，`APPLE_ID=1` 的错绑
    /// 恰好也可能拿不到行而让断言看起来在起作用；3 与 2 不同，绑错必然拿回 banana 行。
    @Test
    void pessimisticLockSingleReturnsTheFilteredRow() {
        ItemEntity row = support.inTransaction(() -> query().where(idRef())
                .eq(CHERRY_ID)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .single());

        assertThat(row).as("`lock(...).single()` 取回的行").isNotNull();
        assertThat(row.id()).as("取回的行必须就是 where 里那个 id").isEqualTo(CHERRY_ID);
        assertThat(row.name()).as("且整行都是那一行，不是别的行").isEqualTo(CHERRY_NAME);
    }

    /// 跑一条查询、取走它下发的语句，返回归一化后的单条 SQL。
    private String sqlOf(Runnable query) {
        support.clearSql();
        query.run();

        List<String> sql = normalized(support.drainSql());
        assertThat(sql).hasSize(1);
        return sql.getFirst();
    }

    private static List<String> normalized(List<String> sql) {
        return sql.stream()
                .map(statement -> statement
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", " ")
                        .trim())
                .toList();
    }

    /// 六列映射器：列序与快照 1 相同，只用于钉住「自定义映射器渲染出的整句与
    /// `ProjectionMappers.select(...)` 一致」。
    private final class DetailMapper implements ProjectionMapper<ItemEntity, Detail> {

        @Override
        public List<Expression<ItemEntity, ?>> expressions() {
            return List.of(
                    Path.of(idRef()),
                    Path.of(nameRef()),
                    Path.of(scoreRef()),
                    Path.of(score2Ref()),
                    Path.of(noteRef()),
                    Path.of(statusRef()));
        }

        @Override
        public Detail map(Tuple row) {
            return new Detail((Long) row.get(0), (Integer) row.get(2), (ItemStatus) row.get(5));
        }
    }

    /// 三列映射器：`id, score, status`，用于「替换」两条快照——列数与 `select(id, name)` 不同，
    /// 替换没生效时整句立刻不同。
    private final class BriefMapper implements ProjectionMapper<ItemEntity, Brief> {

        @Override
        public List<Expression<ItemEntity, ?>> expressions() {
            return List.of(Path.of(idRef()), Path.of(scoreRef()), Path.of(statusRef()));
        }

        @Override
        public Brief map(Tuple row) {
            return new Brief((Long) row.get(0), (Integer) row.get(1), (ItemStatus) row.get(2));
        }
    }

    private record Detail(long id, Integer score, ItemStatus status) {}

    private record Brief(long id, Integer score, ItemStatus status) {}
}
