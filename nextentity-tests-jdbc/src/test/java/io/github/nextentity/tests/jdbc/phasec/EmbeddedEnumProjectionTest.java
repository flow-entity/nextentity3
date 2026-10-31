package io.github.nextentity.tests.jdbc.phasec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jdbc.support.SingleConnectionDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// Phase C 独立端到端验证（**红**测试）：`@Embedded` 内 `@Enumerated(STRING)` 叶子的值投影必须是枚举常量。
///
/// 缺陷机制（[io.github.nextentity.jdbc.exec.RowReader]）：值投影的 `resolve(AttributePath)` 只会沿
/// `association` 下降到目标实体，**从不下降到 `@Embedded` 容器**，所以 `[profile, status]` 的第二段在
/// 根实体上找不到同名属性 → `attribute = null` → `ColumnReader.readCell` 拿到 `null` 列元数据，直接按
/// 列类型 `String` 读出 `"ACTIVE"`。
///
/// 实体读回（`scalarAttributes` 直接给叶子列元数据）与顶层枚举投影都不经过这条下降逻辑，因此前两组是
/// 控制组：它们证明夹具与用例有效性，同时把「只有嵌入叶子的值投影丢类型」这个差异钉死。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddedEnumProjectionTest {

    private static final String CREATE_TABLE = "create table if not exists embedded_enum_item"
            + " (id bigint primary key, state varchar(32), profile_status varchar(32))";
    private static final String INSERT_ROW =
            "insert into embedded_enum_item (id, state, profile_status) values (1, 'NEW', 'ACTIVE')";

    private final SingleConnectionDataSource dataSource = SingleConnectionDataSource.h2("phasec_embedded_enum");
    private final EntityOperations<EmbeddedEnumItemEntity> items =
            new JdbcEntityOperationsFactory(dataSource).operations(EmbeddedEnumItemEntity.class);

    @BeforeEach
    void reset() {
        execute(CREATE_TABLE);
        execute("delete from embedded_enum_item");
        execute(INSERT_ROW);
    }

    @AfterAll
    void closeDataSource() {
        dataSource.close();
    }

    /// 控制组：实体查询把嵌入叶子按列元数据读回，枚举常量与顶层枚举都正确（修复前后都应为绿）。
    @Test
    void entitySelectionKeepsEnumLeaves() {
        List<EmbeddedEnumItemEntity> rows = items.query().list();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).state()).isEqualTo(ItemStatus.NEW);
        assertThat(rows.get(0).profile().status()).isEqualTo(ItemStatus.ACTIVE);
    }

    /// 控制组：顶层 `@Enumerated(STRING)` 列的值投影已经返回枚举常量（修复前后都应为绿）。
    @Test
    void topLevelEnumProjectionKeepsEnumType() {
        List<ItemStatus> states =
                items.query().select(EmbeddedEnumItemEntity::state).list();

        assertThat(states).containsExactly(ItemStatus.NEW);
    }

    /// 缺陷：嵌入叶子的值投影返回列里的 String（`"ACTIVE"`），而不是枚举常量。
    @Test
    void embeddedEnumLeafProjectionKeepsEnumType() {
        List<ItemStatus> statuses = items.query()
                .select(EntityPath.of(EmbeddedEnumItemEntity::profile).get(EmbeddedEnumProfile::status))
                .list();

        assertThat(statuses).containsExactly(ItemStatus.ACTIVE);
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("执行 DDL/DML 失败: " + sql, e);
        }
    }
}
