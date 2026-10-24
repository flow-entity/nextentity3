package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.Predicate;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.ConsistencyTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 下发语句的**整句快照**：把代表性查询渲染出的完整 SQL 逐字钉住。
///
/// 这里是 `having` / `mod` / `min` / `avg` / `distinct` / `not` 六类渲染的唯一断言点。
/// 它们原先只散落在 `ItemTier1RenderingTest` 的 `containsIgnoringCase` 片段断言里——片段对了但整句
/// 结构错（谓词顺序颠倒、括号加错位置、`limit` 落到子查询外、聚合别名丢在 `group by` 里）一条都抓不到，
/// 所以那些片段断言已删除、合并到这里。本类断的是整句，任何结构性改动都必须显式改期望值。
///
/// 快照只对 H2 + 当前 Hibernate 版本成立（换方言 `limit`/`concat`/`avg` 的 cast 渲染就不同），
/// 所以不接 MySQL 通道，也不放进 `nextentity-testing` 的后端中立基类。
///
/// 归一化只做两件不影响语义的事：转小写、把连续空白压成一个空格——格式调整不会误报，
/// 而 token 顺序、括号、占位符个数、`limit`/`offset` 的位置全部在断言范围内。
class ItemSqlSnapshotTest extends ConsistencyTestBase<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_sql_snapshot");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }

    @Test
    void snapshotsRepresentativeStatements() {
        List<String> actual = new ArrayList<>();

        // 1. 单谓词 + 排序：最基础的 select 形状
        actual.add(sqlOf(
                () -> query().where(scoreRef()).ge(20).orderBy(idRef()).asc().list()));

        // 2. 逻辑组合：or 分组后整体取反，括号结构决定语义，压平就会错
        Predicate<ItemEntity> either =
                Path.of(nameRef()).eq(BANANA_NAME).or(Path.of(noteRef()).isNull());
        actual.add(sqlOf(() ->
                query().where(Path.of(scoreRef()).gt(10).and(either.not())).list()));

        // 3. 分组 + 聚合 + having：三段必须各就各位
        actual.add(
                sqlOf(() -> query().select(Path.of(noteRef()), Path.of(idRef()).count())
                        .groupBy(noteRef())
                        .having(Path.of(idRef()).count().gt(1L))
                        .list()));

        // 4. distinct 投影：去重必须落在 SQL 侧
        actual.add(sqlOf(() -> query().distinct()
                .select(Path.of(statusRef()), Path.of(noteRef()))
                .list()));

        // 5. 无分组聚合：整表一个分组，count 与 sum 同排
        actual.add(sqlOf(() -> query().select(
                        Path.of(idRef()).count(), Path.of(scoreRef()).sum())
                .list()));

        // 6. mod 谓词：取模必须落在 SQL 侧，不是在应用侧算
        actual.add(sqlOf(() -> query().where(Path.of(scoreRef()).mod(3).eq(0)).list()));

        // 7. min 聚合：函数名、作用列、无 group by 三段都在快照里
        actual.add(sqlOf(() -> query().select(Path.of(scoreRef()).min()).list()));

        // 8. avg 聚合：同上，外加 `cast(... as float(53))`——score 是整数列，少了这层 cast
        //    平均就会走整数除法被截断，快照把这一步钉住。
        actual.add(sqlOf(() -> query().select(Path.of(scoreRef()).avg()).list()));

        // 9. `not()` 折叠：Hibernate 把 `not(a=?)` 折成 `a<>?`，折叠形态也要逐字钉住。
        //    只投影主键，避免选择列表里的 `name` 列让「语句里出现过 name」蒙对。
        actual.add(sqlOf(() -> query().select(Path.of(idRef()))
                .where(Path.of(nameRef()).eq(BANANA_NAME).not())
                .list()));

        // 10. 分页：两条语句（先 count 再取页），`limit`/`offset` 的数值与位置都在快照里
        support.clearSql();
        query().orderBy(idRef()).asc().slice(1, 2).data();
        actual.addAll(normalized(support.drainSql()));

        assertThat(actual)
                .containsExactly(
                        "select ie1_0.id,ie1_0.name,ie1_0.note,ie1_0.score,ie1_0.score2,ie1_0.status from item ie1_0 where ie1_0.score>=? order by ie1_0.id",
                        "select ie1_0.id,ie1_0.name,ie1_0.note,ie1_0.score,ie1_0.score2,ie1_0.status from item ie1_0 where ie1_0.score>? and not(ie1_0.name=? or ie1_0.note is null)",
                        "select ie1_0.note c0,count(ie1_0.id) c1 from item ie1_0 group by c0 having count(ie1_0.id)>?",
                        "select distinct ie1_0.status,ie1_0.note from item ie1_0",
                        "select count(ie1_0.id),sum(ie1_0.score) from item ie1_0",
                        "select ie1_0.id,ie1_0.name,ie1_0.note,ie1_0.score,ie1_0.score2,ie1_0.status from item ie1_0 where (ie1_0.score%3)=?",
                        "select min(ie1_0.score) from item ie1_0",
                        "select avg(cast(ie1_0.score as float(53))) from item ie1_0",
                        "select ie1_0.id from item ie1_0 where ie1_0.name<>?",
                        "select count(true) from item ie1_0",
                        "select ie1_0.id,ie1_0.name,ie1_0.note,ie1_0.score,ie1_0.score2,ie1_0.status from item ie1_0 order by ie1_0.id offset ? rows fetch first ? rows only");
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
}
