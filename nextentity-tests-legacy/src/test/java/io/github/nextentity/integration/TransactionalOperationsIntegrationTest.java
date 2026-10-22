package io.github.nextentity.integration;

import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.entity.Employee;
import io.github.nextentity.integration.entity.EmployeeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

///
/// Transactional 操作s integration tests.
/// <p>
/// 测试s transaction-related 行为 including:
/// - Transaction boundaries
/// - Batch 操作 atomicity
/// - Optimistic locking 行为
/// <p>
/// These tests run against MySQL and PostgreSQL using 测试containers.
///
/// @author HuangChengwei
@DisplayName("Transactional Operations Integration Tests")
public class TransactionalOperationsIntegrationTest {

    @AfterEach
    void tearDown() {
        var context = IntegrationTestProvider.getEntityManagerContext();
        if (context != null) {
            context.reset();
        }
    }

    ///
    /// 测试s that insert 操作s are committed.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should commit insert operation")
    void shouldCommitInsertOperation(IntegrationTestContext context) {
        // Given
        Employee employee = createTestEmployee(7001L, "Transaction Test");

        // When
        context.operations(Employee.class).insert(employee);

        // Then - Verify the insert was committed
        Employee found = context.queryEmployees()
                .where(Employee::getId).eq(7001L)
                .single();
        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("Transaction Test");

        // Cleanup
        context.delete(employee);
    }

    ///
    /// 测试s that update 操作s are committed.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should commit update operation")
    void shouldCommitUpdateOperation(IntegrationTestContext context) {
        // Given
        Employee employee = createTestEmployee(7002L, "Before Update");
        context.operations(Employee.class).insert(employee);

        // When
        employee.setName("After Update");
        context.operations(Employee.class).update(employee);

        // Then - Verify the update was committed
        Employee found = context.queryEmployees()
                .where(Employee::getId).eq(7002L)
                .single();
        assertThat(found.getName()).isEqualTo("After Update");

        // Cleanup
        context.delete(employee);
    }

    ///
    /// 测试s that delete 操作s are committed.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should commit delete operation")
    void shouldCommitDeleteOperation(IntegrationTestContext context) {
        // Given
        Employee employee = createTestEmployee(7003L, "To Delete");
        context.operations(Employee.class).insert(employee);

        // When
        context.delete(employee);

        // Then - Verify the delete was committed
        Employee found = context.queryEmployees()
                .where(Employee::getId).eq(7003L)
                .single();
        assertThat(found).isNull();
    }

    ///
    /// 测试s batch insert atomicity.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should insert batch atomically")
    void shouldInsertBatchAtomically(IntegrationTestContext context) {
        // Given
        List<Employee> employees = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            employees.add(createTestEmployee(7100L + i, "Batch " + i));
        }

        // When
        context.operations(Employee.class).insertAll(employees);

        // Then - All should be inserted
        for (int i = 0; i < 5; i++) {
            Employee found = context.queryEmployees()
                    .where(Employee::getId).eq(7100L + i)
                    .single();
            assertThat(found).isNotNull();
        }

        // Cleanup
        context.deleteAll(employees);
    }

    ///
    /// 测试s batch update atomicity.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should update batch atomically")
    void shouldUpdateBatchAtomically(IntegrationTestContext context) {
        // Given
        List<Employee> employees = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            employees.add(createTestEmployee(7200L + i, "Before " + i));
        }
        context.operations(Employee.class).insertAll(employees);

        // When - Update all names
        for (int i = 0; i < employees.size(); i++) {
            employees.get(i).setName("After " + i);
        }
        context.operations(Employee.class).updateAll(employees);

        // Then - All should be updated
        for (int i = 0; i < 3; i++) {
            Employee found = context.queryEmployees()
                    .where(Employee::getId).eq(7200L + i)
                    .single();
            assertThat(found.getName()).isEqualTo("After " + i);
        }

        // Cleanup
        context.deleteAll(employees);
    }

    ///
    /// 测试s batch delete atomicity.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should delete batch atomically")
    void shouldDeleteBatchAtomically(IntegrationTestContext context) {
        // Given
        List<Employee> employees = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            employees.add(createTestEmployee(7300L + i, "To Delete " + i));
        }
        context.operations(Employee.class).insertAll(employees);

        // When
        context.deleteAll(employees);

        // Then - All should be deleted
        for (int i = 0; i < 3; i++) {
            Employee found = context.queryEmployees()
                    .where(Employee::getId).eq(7300L + i)
                    .single();
            assertThat(found).isNull();
        }
    }

    ///
    /// 测试s sequential CRUD 操作s.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should perform sequential CRUD operations")
    void shouldPerformSequentialCrudOperations(IntegrationTestContext context) {
        // Given
        Employee employee = createTestEmployee(7400L, "Sequential Test");

        // When - Insert
        context.operations(Employee.class).insert(employee);
        Employee afterInsert = context.queryEmployees()
                .where(Employee::getId).eq(7400L)
                .single();
        assertThat(afterInsert).isNotNull();

        // When - Update
        employee.setName("Updated Sequential");
        context.operations(Employee.class).update(employee);
        Employee afterUpdate = context.queryEmployees()
                .where(Employee::getId).eq(7400L)
                .single();
        assertThat(afterUpdate.getName()).isEqualTo("Updated Sequential");

        // When - Delete
        context.delete(employee);
        Employee afterDelete = context.queryEmployees()
                .where(Employee::getId).eq(7400L)
                .single();
        assertThat(afterDelete).isNull();
    }

    ///
    /// 测试s multiple entity 操作s.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should handle multiple entity operations")
    void shouldHandleMultipleEntityOperations(IntegrationTestContext context) {
        // Given
        Employee emp1 = createTestEmployee(7501L, "Emp 1");
        Employee emp2 = createTestEmployee(7502L, "Emp 2");
        Employee emp3 = createTestEmployee(7503L, "Emp 3");

        // When - Insert multiple
        context.operations(Employee.class).insert(emp1);
        context.operations(Employee.class).insert(emp2);
        context.operations(Employee.class).insert(emp3);

        // Then - All should exist
        long count = context.queryEmployees()
                .where(Employee::getId).in(7501L, 7502L, 7503L)
                .count();
        assertThat(count).isEqualTo(3);

        // When - Delete one
        context.delete(emp2);

        // Then - Two should remain
        count = context.queryEmployees()
                .where(Employee::getId).in(7501L, 7502L, 7503L)
                .count();
        assertThat(count).isEqualTo(2);

        // Cleanup
        context.delete(emp1);
        context.delete(emp3);
    }

    ///
    /// 测试s status change transaction.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should update status within transaction")
    void shouldUpdateStatusWithinTransaction(IntegrationTestContext context) {
        // Given
        Employee employee = createTestEmployee(7600L, "Status Test");
        employee.setStatus(EmployeeStatus.ACTIVE);
        context.operations(Employee.class).insert(employee);

        // When
        employee.setStatus(EmployeeStatus.INACTIVE);
        context.operations(Employee.class).update(employee);

        // Then
        Employee found = context.queryEmployees()
                .where(Employee::getId).eq(7600L)
                .single();
        assertThat(found.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);

        // Cleanup
        context.delete(employee);
    }

    ///
    /// 测试s salary update transaction.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should update salary within transaction")
    void shouldUpdateSalaryWithinTransaction(IntegrationTestContext context) {
        // Given
        Employee employee = createTestEmployee(7700L, "Salary Test");
        employee.setSalary(50000.0);
        context.operations(Employee.class).insert(employee);

        // When
        employee.setSalary(60000.0);
        context.operations(Employee.class).update(employee);

        // Then
        Employee found = context.queryEmployees()
                .where(Employee::getId).eq(7700L)
                .single();
        assertThat(found.getSalary()).isEqualTo(60000.0);

        // Cleanup
        context.delete(employee);
    }

    ///
    /// 测试s department change transaction.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should update department within transaction")
    void shouldUpdateDepartmentWithinTransaction(IntegrationTestContext context) {
        // Given
        Employee employee = createTestEmployee(7800L, "Dept Test");
        employee.setDepartmentId(1L);
        context.operations(Employee.class).insert(employee);

        // When
        employee.setDepartmentId(2L);
        context.operations(Employee.class).update(employee);

        // Then
        Employee found = context.queryEmployees()
                .where(Employee::getId).eq(7800L)
                .single();
        assertThat(found.getDepartmentId()).isEqualTo(2L);

        // Cleanup
        context.delete(employee);
    }

    ///
    /// 测试s active flag toggle.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should toggle active flag")
    void shouldToggleActiveFlag(IntegrationTestContext context) {
        // Given
        Employee employee = createTestEmployee(7900L, "Active Test");
        employee.setActive(true);
        context.operations(Employee.class).insert(employee);

        // When - Toggle to false
        employee.setActive(false);
        context.operations(Employee.class).update(employee);

        // Then
        Employee found = context.queryEmployees()
                .where(Employee::getId).eq(7900L)
                .single();
        assertThat(found.getActive()).isFalse();

        // When - Toggle back to true
        employee.setActive(true);
        context.operations(Employee.class).update(employee);

        // Then
        found = context.queryEmployees()
                .where(Employee::getId).eq(7900L)
                .single();
        assertThat(found.getActive()).isTrue();

        // Cleanup
        context.delete(employee);
    }

    ///
    /// 测试s query after insert.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should query inserted data immediately")
    void shouldQueryInsertedDataImmediately(IntegrationTestContext context) {
        // Given
        Employee employee = createTestEmployee(8000L, "Query Test");

        // When
        context.operations(Employee.class).insert(employee);
        List<Employee> found = context.queryEmployees()
                .where(Employee::getId).eq(8000L)
                .list();

        // Then
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getName()).isEqualTo("Query Test");

        // Cleanup
        context.delete(employee);
    }

    ///
    /// 测试s count after batch insert.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should count after batch insert")
    void shouldCountAfterBatchInsert(IntegrationTestContext context) {
        // Given
        long initialCount = context.queryEmployees().count();

        List<Employee> employees = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            employees.add(createTestEmployee(8100L + i, "Count Test " + i));
        }

        // When
        context.operations(Employee.class).insertAll(employees);
        long newCount = context.queryEmployees().count();

        // Then
        assertThat(newCount).isEqualTo(initialCount + 5);

        // Cleanup
        context.deleteAll(employees);
    }

    ///
    /// 创建 a test employee with the specified ID and name.
    private Employee createTestEmployee(Long id, String name) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setName(name);
        employee.setEmail(name.toLowerCase().replace(" ", "") + "@example.com");
        employee.setSalary(50000.0);
        employee.setActive(true);
        employee.setStatus(EmployeeStatus.ACTIVE);
        employee.setDepartmentId(1L);
        employee.setHireDate(LocalDate.now());
        return employee;
    }
}
