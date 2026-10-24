package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 多列 `groupBy` 的**下发语句**断言：`GROUP BY` 子句里必须是两个不同的分组项，且顺序与调用顺序一致。
///
/// 其余第一梯队能力（`having` / `mod` / `min` / `avg` / `distinct` / `not`）的渲染证据已全部收进
/// [ItemSqlSnapshotTest] 的整句快照——那里钉的是整条语句，片段断言抓不到的结构性错误
/// （谓词顺序、括号位置、`limit` 落错层级）它能抓到，因此本类不再重复保留片段断言。
///
/// 本类留下的这一条不是整句快照能替代的形态：两列 `groupBy` 的语句文本取决于别名分配，
/// 而这里要钉的恰恰是「两个分组项互不相同且按调用顺序排列」，按项拆开断言比整句更容易定位回归。
class ItemTier1RenderingTest {

    private final JpaItemSupport support = new JpaItemSupport("item_tier1_rendering");

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 两列 `groupBy`：`GROUP BY` 子句必须按调用顺序列出两个**不同**的分组项。
    ///
    /// 只数逗号个数（`split(",").hasSize(2)`）挡不住 `group by c0,c0`：分组列写重复时
    /// 逗号仍是两个，语义却退化成单列分组（每组再按 `status` 拆开的行会被并成一行）。
    /// 精确列表同时钉住顺序（`groupBy(a).groupBy(b)` 与 `groupBy(b).groupBy(a)` 的
    /// 结果行顺序不同）与内容。
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
        // Hibernate 在部分方言上用选择列别名分组（`group by c0,c1`），所以按别名断而不是按列名断。
        assertThat(Arrays.stream(grouped.split(",")).map(String::trim).toList())
                .as("GROUP BY 子句必须按调用顺序列出两个不同的分组项：%s", grouped)
                .containsExactly("group by c0", "c1");
    }
}
