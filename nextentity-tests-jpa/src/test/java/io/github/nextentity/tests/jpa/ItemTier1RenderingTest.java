package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 第一梯队能力的**下发语句**断言：只证明"能力确实落到了 SQL"，不锁死整条语句文本。
///
/// 一致性别名用例（`nextentity-testing`）证明结果集对；本类补另外一半证据——渲染出口出现过
/// `having` / `mod(` / `min(` / `avg(` / `not` / 两个分组列。断言一律 `containsIgnoringCase`，
/// 避免把方言的引号、别名、大小写写进期望值。
class ItemTier1RenderingTest {

    private final JpaItemSupport support = new JpaItemSupport("item_tier1_rendering");

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// `having` 必须出现在语句里（不是被折进 `where`）。
    @Test
    void havingIsRendered() {
        support.clearSql();

        support.query()
                .select(Path.of(support.noteRef()), Path.of(support.idRef()).count())
                .groupBy(support.noteRef())
                .having(Path.of(support.idRef()).count().gt(1L))
                .list();

        assertThat(support.drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("having"));
    }

    /// 取模渲染成 SQL 的 `mod(...)`，而不是在应用侧算。
    @Test
    void modIsRenderedAsSqlFunction() {
        support.clearSql();

        support.query().where(Path.of(support.scoreRef()).mod(3).eq(0)).list();

        // Hibernate 把取模渲染成 `%` 运算符（`(score%3)`），部分方言是 `mod(...)`；两者都证明落到了 SQL 侧。
        assertThat(support.drainSql()).anySatisfy(sql -> assertThat(sql)
                .satisfiesAnyOf(
                        withFunction -> assertThat(withFunction).containsIgnoringCase("mod("),
                        withOperator -> assertThat(withOperator).contains("%3")));
    }

    /// `min` 聚合渲染成 `min(...)`。
    @Test
    void minIsRenderedAsAggregate() {
        support.clearSql();

        support.query().select(Path.of(support.scoreRef()).min()).list();

        assertThat(support.drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("min("));
    }

    /// `avg` 聚合渲染成 `avg(...)`。
    @Test
    void avgIsRenderedAsAggregate() {
        support.clearSql();

        support.query().select(Path.of(support.scoreRef()).avg()).list();

        assertThat(support.drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("avg("));
    }

    /// `not()` 必须真的下推到语句里。
    ///
    /// 实测（H2 + Hibernate 7）：`eq(...).not()` 被**折成** `name<>?`，而不是 `not (name=?)`——
    /// 否定在 SQL AST 里被吸收进比较运算符。所以断言接受两种形态，并把「折叠」这个事实写在这里，
    /// 免得后人以为 `not (` 是唯一正确答案。
    ///
    /// 只投影主键：否则选择列表里的 `note` 列会让 `contains("not")` 假通过。
    @Test
    void notIsRendered() {
        support.clearSql();

        support.query()
                .select(Path.of(support.idRef()))
                .where(Path.of(support.nameRef()).eq("banana").not())
                .list();

        assertThat(support.drainSql()).anySatisfy(sql -> assertThat(sql)
                .satisfiesAnyOf(folded -> assertThat(folded).contains("<>"), explicit -> assertThat(explicit)
                        .containsIgnoringCase("not (")));
    }

    /// 两列 `groupBy` 必须在 `GROUP BY` 子句里同时出现两个列名。
    @Test
    void bothGroupColumnsAppearInGroupByClause() {
        support.clearSql();

        support.query()
                .select(
                        Path.of(support.noteRef()),
                        Path.of(support.statusRef()),
                        Path.of(support.idRef()).count())
                .groupBy(support.noteRef())
                .groupBy(support.statusRef())
                .list();

        List<String> sqls = support.drainSql();
        String withGroupBy = sqls.stream()
                .filter(sql -> sql.toLowerCase(Locale.ROOT).contains("group by"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有抓到带 GROUP BY 的语句：" + sqls));

        String grouped =
                withGroupBy.substring(withGroupBy.toLowerCase(Locale.ROOT).indexOf("group by"));
        // Hibernate 在部分方言上用选择列别名分组（`group by c0,c1`），所以数分组项而不是匹配列名。
        assertThat(grouped.split(",")).as("GROUP BY 子句必须包含两个分组项：%s", grouped).hasSize(2);
    }
}
