package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.testing.ConsistencyTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 3.1.0 新能力面的整句 SQL 快照（H2 + 真 Hibernate）：字符串函数、NOT LIKE 折叠、条件 UPDATE/DELETE、
/// 分组计数派生表。
///
/// 与 [`ItemSqlSnapshotTest`] 分开：那边是 3.0.0 已有的单表谓词 / 聚合 / 窗口，这边是 3.1.0 恢复的能力——
/// 混在一个用例里，「旧能力回归」和「新能力实现差异」会变成同一个红点，看不出是谁坏了。
class ItemCapabilitySqlSnapshotTest extends ConsistencyTestBase<ItemEntity> {

    private static final String SELECT_ITEM =
            "select ie1_0.id,ie1_0.name,ie1_0.note,ie1_0.score," + "ie1_0.score2,ie1_0.status from item ie1_0";

    private final JpaItemSupport support = new JpaItemSupport("item_capability_sql_snapshot");

    @Override
    protected JpaItemSupport support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 四个字符串函数的整句：函数名与参数位置由 Hibernate 决定，这里把渲染结果钉住。
    @Test
    void stringFunctionStatementsAreSnapshotted() {
        List<String> statements = new ArrayList<>();
        statements.add(sqlOf(() -> query().where(nameRef()).lower().eq("apple").list()));
        statements.add(sqlOf(() -> query().where(nameRef()).upper().eq("APPLE").list()));
        statements.add(sqlOf(() -> query().where(nameRef()).trim().eq("apple").list()));
        statements.add(sqlOf(() -> query().where(nameRef()).length().gt(4).list()));

        assertThat(statements)
                .containsExactly(
                        SELECT_ITEM + " where lower(ie1_0.name)=?",
                        SELECT_ITEM + " where upper(ie1_0.name)=?",
                        // Hibernate 把 TRIM 渲染成 SQL 标准形态 trim(both from x)，不是 trim(x)
                        SELECT_ITEM + " where trim(both from ie1_0.name)=?",
                        // LENGTH 落到标准的 character_length(...)
                        SELECT_ITEM + " where character_length(ie1_0.name)>?");
    }

    /// `substring(offset)`（省略长度）必须只带两个参数：3.0.0 之前的实现下发过 `2147483647` 这类
    /// 「到结尾」的巨数值字面量，是可移植性坑（部分方言位数不够 / 被当成非法长度）。
    ///
    /// 函数名两种合法渲染都认：H2 方言渲 `substring`，PostgreSQL 方言渲 `substr`（H2 的 PG
    /// 兼容通道两种都合法）。要钉的是「参数个数」，不是函数名拼写。
    @Test
    void substringWithoutLengthPassesTwoArguments() {
        String statement =
                sqlOf(() -> query().where(nameRef()).substring(1).eq("pple").list());

        assertThat(statement).containsPattern("subs(?:tring|tr)\\(ie1_0\\.name");
        assertThat(statement).doesNotContain("2147483647");
        assertThat(argumentCount(statement, "subs(?:tring|tr)")).as(statement).isEqualTo(2);

        String ranged =
                sqlOf(() -> query().where(nameRef()).substring(1, 2).eq("pp").list());
        assertThat(argumentCount(ranged, "subs(?:tring|tr)")).as(ranged).isEqualTo(3);
    }

    /// `notLike` 在 core 里折成 `NOT(LIKE ...)`；Hibernate 再把它归一成 SQL 的 `not like`（不是 `not (x like ?)`），
    /// 并自动补 `escape ''`（LIKE 转义约定，方言/驱动都认）。
    @Test
    void notLikeIsRenderedAsNotLike() {
        String statement = sqlOf(() -> query().where(nameRef()).notContains("e").list());

        assertThat(statement).contains("ie1_0.name not like ? escape ''");
        assertThat(statement).doesNotContain("not (");
    }

    /// 条件 UPDATE / DELETE 的整句：SET 只含被赋值的列，where 只含条件；Hibernate 给 UPDATE/DELETE 也带别名。
    @Test
    void conditionalWriteStatementsAreSnapshotted() {
        List<String> statements = new ArrayList<>();
        statements.add(sqlOf(() -> support.inTransaction(() -> support.operations()
                .update()
                .set(Path.of(scoreRef()), 99)
                .where(idRef())
                .eq(1L)
                .execute())));
        statements.add(sqlOf(() -> support.inTransaction(
                () -> support.operations().delete().where(idRef()).eq(2L).execute())));

        assertThat(statements)
                .containsExactly(
                        "update item ie1_0 set score=? where ie1_0.id=?", "delete from item ie1_0 where ie1_0.id=?");
    }

    /// 分组计数是一条派生表 SQL：`select count(*) from (分组查询)`，不是「把组全查出来在客户端数」。
    @Test
    void groupCountUsesDerivedTable() {
        String statement = sqlOf(() -> query().groupBy(statusRef()).count());

        assertThat(statement).contains("select count(*) from (").contains("group by");
        assertThat(statement).doesNotContain("fetch first");
    }

    /// `slice(offset, limit)` 在 `groupBy` 之后发两条语句：派生表计数（total=组数）+ 带窗口的分组查询。
    ///
    /// 两条都必须保留 `group by`——否则 total 数的是行数、页大小也按行生效，正是 3.1.0 之前
    /// 「分组分页等于没分页」的那种静默错。
    @Test
    void groupSliceUsesDerivedCountAndWindowedGroupQuery() {
        support.clearSql();
        query().select(Path.of(statusRef()), Path.of(idRef()).count())
                .groupBy(statusRef())
                .orderBy(statusRef())
                .asc()
                .slice(0, 2);
        List<String> statements = normalized(support.drainSql());

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0))
                .as("第一条是 total 的派生表计数")
                .contains("select count(*) from (")
                .contains("group by");
        assertThat(statements.get(1)).as("第二条是真正取页的分组查询，窗口落在 SQL 里").contains("group by");
        assertWindowRendered(statements.get(1));
    }

    /// `Collector.list(offset, limit)` 只发一条带窗口的分组查询（不要 total），窗口同样落在组上。
    @Test
    void collectorWindowEmitsSingleWindowedGroupQuery() {
        String statement = sqlOf(
                () -> query().select(Path.of(statusRef()), Path.of(idRef()).count())
                        .groupBy(statusRef())
                        .orderBy(statusRef())
                        .asc()
                        .list(1, 1));

        assertThat(statement).contains("group by");
        assertWindowRendered(statement);
    }

    /// 窗口子句的方言写法：MySQL/MariaDB 是 `limit`，PostgreSQL 是 `fetch first ? rows only`，
    /// H2 两种模式各随其一。钉「有没有窗口」而不是钉具体关键字——否则同一条用例在
    /// `-Pdialect-pg` 下会红成方言噪音，掩盖真正的「窗口没下发」。
    private static void assertWindowRendered(String statement) {
        assertThat(statement).as("分组查询必须把窗口下推到 SQL：%s", statement).containsPattern("(limit|offset|fetch first)");
    }

    private String sqlOf(Runnable query) {
        support.clearSql();
        query.run();
        List<String> statements = normalized(support.drainSql());
        assertThat(statements).hasSize(1);
        return statements.getFirst();
    }

    /// 数某个函数调用里的参数个数（顶层逗号分隔）。
    private static int argumentCount(String statement, String function) {
        Matcher matcher = Pattern.compile(function + "\\(").matcher(statement);
        assertThat(matcher.find()).as("SQL 里应有 %s(: %s", function, statement).isTrue();
        int depth = 0;
        int commas = 0;
        boolean opened = false;
        for (int index = matcher.end() - 1; index < statement.length(); index++) {
            char ch = statement.charAt(index);
            if (ch == '(') {
                depth++;
                opened = true;
            } else if (ch == ')') {
                depth--;
                if (depth == 0 && opened) {
                    return commas + 1;
                }
            } else if (ch == ',' && depth == 1) {
                commas++;
            }
        }
        throw new AssertionError("函数没有闭合: " + statement);
    }

    private static List<String> normalized(List<String> raw) {
        List<String> result = new ArrayList<>(raw.size());
        for (String sql : raw) {
            result.add(sql.toLowerCase().replaceAll("\\s+", " ").trim());
        }
        return result;
    }
}
