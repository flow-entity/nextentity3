package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMappers;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.MultiColumnProjectionConsistencyTest;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 把四列及以上投影的一致性用例接到 JPA 后端上（用例本体在 nextentity-testing 模块），
/// 并补一条**下发语句**断言：能力确实落到 SQL 的投影子句，而不是应用侧拼装。
class ItemMultiColumnProjectionConsistencyTest extends MultiColumnProjectionConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_multi_column");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 四列必须按实参顺序出现在投影子句里，且**只有**这四列。
    ///
    /// 整句快照（转小写 + 连续空白压一个空格，同 `ItemSqlSnapshotTest` 口径）：原先用
    /// `containsSubsequence(".id", ".name", ".score", ".status")` 时，投影里混进多余列
    /// （`select id,name,score,score2,status`）、列被重复、或多带一个 `distinct`，子序列仍然成立
    /// ——而「投影列集与 `expressions()` 完全一致」正是这条用例要证明的东西。
    ///
    /// 快照只对 **H2 + 当前 Hibernate** 成立（表别名 `ie1_0` 来自 Hibernate 渲染）。
    @Test
    void fourColumnsRenderInOrder() {
        support.clearSql();

        support.query()
                .project(ProjectionMappers.select(
                        Path.of(support.idRef()),
                        Path.of(support.nameRef()),
                        Path.of(support.scoreRef()),
                        Path.of(support.statusRef())))
                .list();

        assertThat(normalized(support.drainSql()))
                .containsExactly("select ie1_0.id,ie1_0.name,ie1_0.score,ie1_0.status from item ie1_0");
    }

    /// 与 `ItemSqlSnapshotTest` 同一套归一化：转小写 + 连续空白压一个空格，其余字符原样比对。
    private static List<String> normalized(List<String> sqls) {
        return sqls.stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim())
                .toList();
    }
}
