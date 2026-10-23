package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMappers;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.MultiColumnProjectionConsistencyTest;
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

    /// 四列必须按实参顺序出现在投影子句里（用 `containsSubsequence` 避开门别名与引号的方言差异）。
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

        assertThat(support.drainSql())
                .anySatisfy(sql -> assertThat(sql).containsSubsequence(".id", ".name", ".score", ".status"));
    }
}
