package io.github.nextentity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.entity.Employee;
import io.github.nextentity.integration.entity.EmployeeStatus;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

/// 配置行为集成测试（3.x 收敛版）。
///
/// 2.2.3 这个类验证的是 `JdbcConfig` / `LoggingConfig` / `SqlLogger` 三套配置：`inlineNumericLiterals`、
/// `batch.enabled`/`batch.size`、SQL 日志开关。3.x 没有这三套配置（没有 `io.github.nextentity.jdbc`、
/// 没有 `core.LoggingConfig`、没有 `core.SqlLogger`），所以那 7 个用例按移植口径删除并记档；
/// 保留的 `CRUD Operations` 一节与配置无关，是同一份夹具上的真实读写断言。
@DisplayName("Configuration Behavior Integration Tests")
public class ConfigurationBehaviorIntegrationTest {

    @AfterEach
    void resetContext() {
        var context = IntegrationTestProvider.getEntityManagerContext();
        if (context != null) {
            context.reset();
        }
    }

    @Nested
    @DisplayName("CRUD Operations")
    class CrudOperationsTests {

        /// 验证插入操作
        @ParameterizedTest
        @ArgumentsSource(IntegrationTestProvider.class)
        @DisplayName("插入操作成功")
        void testInsert(IntegrationTestContext context) {
            // Given
            Employee employee = createTestEmployee(9201L, "Insert Test");

            // When
            context.operations(Employee.class).insert(employee);

            // Then
            Employee inserted = context.queryEmployees()
                    .where(Employee::getId).eq(9201L)
                    .single();
            assertThat(inserted).isNotNull();
            assertThat(inserted.getName()).isEqualTo("Insert Test");
        }

        /// 验证更新操作
        @ParameterizedTest
        @ArgumentsSource(IntegrationTestProvider.class)
        @DisplayName("更新操作成功")
        void testUpdate(IntegrationTestContext context) {
            // Given
            Employee employee = context.queryEmployees()
                    .where(Employee::getId).eq(1L)
                    .list().get(0);
            String originalName = employee.getName();
            employee.setName("Updated Name");

            // When
            context.operations(Employee.class).update(employee);

            // Then
            Employee updated = context.queryEmployees()
                    .where(Employee::getId).eq(1L)
                    .single();
            assertThat(updated.getName()).isEqualTo("Updated Name");
            assertThat(updated.getName()).isNotEqualTo(originalName);
        }

        /// 验证删除操作
        @ParameterizedTest
        @ArgumentsSource(IntegrationTestProvider.class)
        @DisplayName("删除操作成功")
        void testDelete(IntegrationTestContext context) {
            // Given
            Employee employee = createTestEmployee(9301L, "Delete Test");
            context.operations(Employee.class).insert(employee);

            // 验证插入成功
            Employee inserted = context.queryEmployees()
                    .where(Employee::getId).eq(9301L)
                    .single();
            assertThat(inserted).isNotNull();

            // When
            context.delete(inserted);

            // Then
            List<Employee> deleted = context.queryEmployees()
                    .where(Employee::getId).eq(9301L)
                    .list();
            assertThat(deleted).isEmpty();
        }

        /// 验证查询操作
        @ParameterizedTest
        @ArgumentsSource(IntegrationTestProvider.class)
        @DisplayName("查询操作成功")
        void testQuery(IntegrationTestContext context) {
            // When
            List<Employee> employees = context.queryEmployees()
                    .where(Employee::getStatus).eq(EmployeeStatus.ACTIVE)
                    .orderBy(Employee::getName).asc()
                    .slice(0, 10).data();

            // Then
            assertThat(employees).isNotEmpty();
            assertThat(employees.size()).isLessThanOrEqualTo(10);
        }
    }

    // ==================== 辅助方法 ====================

    private Employee createTestEmployee(Long id, String name) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setName(name);
        employee.setEmail("test" + id + "@example.com");
        employee.setSalary(50000.0);
        employee.setActive(true);
        employee.setStatus(EmployeeStatus.ACTIVE);
        employee.setDepartmentId(1L);
        employee.setHireDate(LocalDate.now());
        return employee;
    }
}
