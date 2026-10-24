package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.StringPath;
import io.github.nextentity.testing.AssetFixture;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// 关联嵌套路径的整句 SQL 快照（H2 + 真 Hibernate）。
///
/// 与 [`ItemSqlSnapshotTest`] 互补：那边钉单表谓词 / 聚合 / 窗口的整句形态，这里钉**关联**——
/// `owner` 是 `@ManyToOne`，嵌套路径的 join 形态（LEFT JOIN 还是 INNER JOIN、是否被翻成子查询、
/// 分页的 `total` 是否被 join 放大）是最容易被「改谓词编译」悄悄改坏的地方。
///
/// 断言的是 Hibernate 渲染后的整句：子串断言（`containsIgnoringCase("join")`）挡不住
/// 「join 类型被换」「多了/少了 distinct」「offset 变成内存分页」这类回归。
class AssetSqlSnapshotTest {

    private static final String ALPHA = "alpha";

    private final JpaAssetSupport support = new JpaAssetSupport("asset_sql_snapshot");

    /// 固定夹具（与 [`io.github.nextentity.testing.AssociationConsistencyTest`] 同形）：
    /// anvil/axe 属于 alpha，bolt 属于 beta，bandage 无关联。
    @BeforeEach
    void seed() {
        support.seedData(List.of(
                new AssetFixture(1L, "anvil", ALPHA),
                new AssetFixture(2L, "axe", ALPHA),
                new AssetFixture(3L, "bolt", "beta"),
                new AssetFixture(4L, "bandage", null)));
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 嵌套路径条件 / 关联为空 / 关联分页，三种形态的整句 SQL。
    @Test
    void associationStatementsAreSnapshotted() {
        StringPath<AssetEntity> ownerName = support.ownerNamePath();

        List<String> statements = new ArrayList<>();
        statements.add(sqlOf(() -> support.query().where(ownerName.eq(ALPHA)).list()));
        statements.add(sqlOf(() -> support.query().where(ownerName.isNull()).list()));
        statements.addAll(
                sqlsOf(() -> support.query().where(ownerName.eq(ALPHA)).slice(0, 2)));
        statements.addAll(
                sqlsOf(() -> support.query().where(ownerName.eq(ALPHA)).slice(1, 2)));

        assertThat(statements)
                .containsExactly(
                        "select ae1_0.id,ae1_0.name,ae1_0.owner_id from asset ae1_0"
                                + " left join owner o1_0 on o1_0.id=ae1_0.owner_id where o1_0.name=?",
                        "select ae1_0.id,ae1_0.name,ae1_0.owner_id from asset ae1_0"
                                + " left join owner o1_0 on o1_0.id=ae1_0.owner_id where o1_0.name is null",
                        "select count(true) from asset ae1_0"
                                + " left join owner o1_0 on o1_0.id=ae1_0.owner_id where o1_0.name=?",
                        "select ae1_0.id,ae1_0.name,ae1_0.owner_id from asset ae1_0"
                                + " left join owner o1_0 on o1_0.id=ae1_0.owner_id where o1_0.name=?"
                                + " order by ae1_0.id fetch first ? rows only",
                        "select count(true) from asset ae1_0"
                                + " left join owner o1_0 on o1_0.id=ae1_0.owner_id where o1_0.name=?",
                        "select ae1_0.id,ae1_0.name,ae1_0.owner_id from asset ae1_0"
                                + " left join owner o1_0 on o1_0.id=ae1_0.owner_id where o1_0.name=?"
                                + " order by ae1_0.id offset ? rows fetch first ? rows only");
    }

    /// 跑一条查询、取走它下发的语句，返回归一化后的单条 SQL。
    private String sqlOf(Runnable query) {
        List<String> sql = sqlsOf(query);
        assertThat(sql).hasSize(1);
        return sql.get(0);
    }

    /// 跑一条查询、取走它下发的全部语句（窗口查询会先 count 再取列表），归一化后返回。
    private List<String> sqlsOf(Runnable query) {
        support.clearSql();
        query.run();
        return normalized(support.drainSql());
    }

    /// 归一化：小写 + 连续空白压成单空格。
    private static List<String> normalized(List<String> raw) {
        List<String> result = new ArrayList<>(raw.size());
        for (String sql : raw) {
            result.add(sql.toLowerCase().replaceAll("\\s+", " ").trim());
        }
        return result;
    }
}
