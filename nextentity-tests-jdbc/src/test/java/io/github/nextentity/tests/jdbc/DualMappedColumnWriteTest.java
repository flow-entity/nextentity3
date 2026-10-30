package io.github.nextentity.tests.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.tests.jdbc.support.JdbcSchema;
import io.github.nextentity.tests.jdbc.support.SingleConnectionDataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// legacy `Employee` 的同列双映射形状（JDBC 通道）：标量字段 `departmentId` 与只读关联 `department`
/// 映射同一列 `department_id` 时，写侧必须**只写一次**该列，值取可写的那一侧，
/// 而可写关联（`manager_department_id`，反向对照）照常写。
///
/// oracle 是**真库读回的两个列值 + 不炸重复列**：只断言"不抛异常"的话，把写侧整个砍掉、
/// 或把只读关联也当可写列塞进语句（同一列出现两次，H2/MySQL 直接拒绝），两条路的绿都不该算证据。
///
/// 与 tests-jpa 的 `DualMappedColumnWriteTest` 是同一形状的两后端对拍：JPA 侧曾因
/// `set departmentId=?, departmentId=?` 让 legacy 出 10 个 `Duplicate column name` 错误，
/// JDBC 侧靠 `ReadOnly` 过滤没炸，但此前没有任何用例覆盖这个形状。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DualMappedColumnWriteTest {

    private static final long ALICE_ID = 1L;

    private final SingleConnectionDataSource dataSource = SingleConnectionDataSource.h2("dual_mapped_column_write");

    private final EntityOperations<DualMappedEmployeeEntity> employees;

    DualMappedColumnWriteTest() {
        JdbcSchema.apply(dataSource.connection(), JdbcSchema.H2);
        this.employees = new JdbcEntityOperationsFactory(dataSource).operations(DualMappedEmployeeEntity.class);
    }

    @BeforeEach
    void seed() {
        execute("delete from dual_mapped_employee");
        execute("delete from dual_mapped_department");
        execute("insert into dual_mapped_department (id, name) values (1, 'eng'), (2, 'ops'), (3, 'lab')");
        execute("insert into dual_mapped_employee (id, name, department_id) values (1, 'alice', 1)");
    }

    @AfterAll
    void closeDataSource() {
        dataSource.close();
    }

    @Test
    @DisplayName("insert：同一列只写一次，值取标量字段；可写关联（反向对照）照常写")
    void insertWritesSharedColumnOnce() {
        employees.insert(new DualMappedEmployeeEntity(2L, "bob", 2L, department(3L), department(3L)));

        assertThat(columnOf("department_id", 2L))
                .as("department_id 来自可写的标量字段（2），不是只读关联指向的 3")
                .isEqualTo(2L);
        assertThat(columnOf("manager_department_id", 2L))
                .as("可写关联必须照常写：否则「无条件跳过所有关联」也能让本用例全绿")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("insert：只读关联独自非空时不写该列（列只能是 NULL，不能是关联的 @Id）")
    void readOnlyAssociationAloneDoesNotWriteColumn() {
        employees.insert(new DualMappedEmployeeEntity(3L, "carol", null, department(1L), null));

        assertThat(columnOf("department_id", 3L))
                .as("标量字段为 null 时列必须是 NULL；写成只读关联的 @Id 说明写侧没看可写标志")
                .isNull();
    }

    @Test
    @DisplayName("update：SET 里同一列只写一次，值取标量字段，可写关联照常更新")
    void updateWritesSharedColumnOnce() {
        employees.update(new DualMappedEmployeeEntity(ALICE_ID, "alice-updated", 2L, department(3L), department(1L)));

        assertThat(columnOf("department_id", ALICE_ID))
                .as("department_id 取标量字段 2，不是只读关联的 3")
                .isEqualTo(2L);
        assertThat(columnOf("manager_department_id", ALICE_ID)).as("可写关联必须照常更新").isEqualTo(1L);
    }

    /// 只带主键的关联对象：JDBC 写关联只读 `@Id`。
    private static DualMappedDepartmentEntity department(long id) {
        return new DualMappedDepartmentEntity(id, "department-" + id);
    }

    private void execute(String sql) {
        try (Statement statement = dataSource.connection().createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 执行失败: " + sql, e);
        }
    }

    private Long columnOf(String column, long id) {
        try (PreparedStatement statement = dataSource
                .connection()
                .prepareStatement("select " + column + " from dual_mapped_employee where id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("dual_mapped_employee 行不存在: " + id);
                }
                long value = rows.getLong(1);
                return rows.wasNull() ? null : value;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取列失败: " + column + " @ " + id, e);
        }
    }
}
