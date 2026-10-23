package io.github.nextentity.integration;

import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.entity.Department;
import io.github.nextentity.integration.entity.Employee;
import io.github.nextentity.integration.entity.EmployeeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

///
/// CRUD 操作s integration tests.
/// <p>
/// 测试s insert, update, and delete 操作s including:
/// - Single entity insert/update/delete
/// - Batch insert/update/delete
/// - Optimistic locking
/// - Partial field updates
/// <p>
/// These tests run against MySQL and PostgreSQL using 测试containers.
///
/// @author HuangChengwei
@DisplayName("CRUD Operations Integration Tests")
public class CrudOperationsIntegrationTest {

    @AfterEach
    void tearDown() {
        var context = IntegrationTestProvider.getEntityManagerContext();
        if (context != null) {
            context.reset();
        }
    }

    ///
    /// 测试s inserting a single employee.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should insert single employee")
    void shouldInsertSingleEmployee(IntegrationTestContext context) {
        // Given
        Employee newEmployee = createTestEmployee(100L, "Test User", "test@example.com");

        // When
        context.operations(Employee.class).insert(newEmployee);

        // Then
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getId).eq(100L)
                .list();
        assertThat(employees).hasSize(1);
        assertThat(employees.get(0).getName()).isEqualTo("Test User");
        assertThat(employees.get(0).getEmail()).isEqualTo("test@example.com");
    }

    ///
    /// 测试s inserting multiple employees.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should insert multiple employees")
    void shouldInsertMultipleEmployees(IntegrationTestContext context) {
        // Given
        List<Employee> newEmployees = new ArrayList<>();
        newEmployees.add(createTestEmployee(200L, "User 200", "user200@example.com"));
        newEmployees.add(createTestEmployee(201L, "User 201", "user201@example.com"));
        newEmployees.add(createTestEmployee(202L, "User 202", "user202@example.com"));

        // When
        context.operations(Employee.class).insertAll(newEmployees);

        // Then
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getId).in(200L, 201L, 202L)
                .orderBy(Employee::getId).asc()
                .list();
        assertThat(employees).hasSize(3);
        assertThat(employees.get(0).getName()).isEqualTo("User 200");
        assertThat(employees.get(2).getName()).isEqualTo("User 202");
    }

    ///
    /// 测试s inserting a department.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should insert single department")
    void shouldInsertSingleDepartment(IntegrationTestContext context) {
        // Given
        Department newDept = new Department(100L, "IT", "Building E", 500000.0, true);

        // When
        context.operations(Department.class).insert(newDept);

        // Then
        List<Department> departments = context.queryDepartments()
                .where(Department::getId).eq(100L)
                .list();
        assertThat(departments).hasSize(1);
        assertThat(departments.get(0).getName()).isEqualTo("IT");
        assertThat(departments.get(0).getLocation()).isEqualTo("Building E");
    }

    ///
    /// 测试s updating a single employee.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should update single employee")
    void shouldUpdateSingleEmployee(IntegrationTestContext context) {
        // Given
        Employee employee = context.queryEmployees()
                .where(Employee::getId).eq(1L)
                .list().get(0);
        String originalName = employee.getName();
        employee.setName("Updated Name");
        employee.setSalary(99999.0);

        // When
        context.operations(Employee.class).update(employee);

        // Then
        Employee updated = context.queryEmployees()
                .where(Employee::getId).eq(1L)
                .list().get(0);
        assertThat(updated.getName()).isEqualTo("Updated Name");
        assertThat(updated.getSalary()).isEqualTo(99999.0);
        assertThat(updated.getName()).isNotEqualTo(originalName);
    }

    ///
    /// 测试s updating multiple employees.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should update multiple employees")
    void shouldUpdateMultipleEmployees(IntegrationTestContext context) {
        // Given
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getDepartmentId).eq(1L)
                .list();

        for (Employee emp : employees) {
            emp.setSalary(emp.getSalary() + 1000);
        }

        // When
        context.operations(Employee.class).updateAll(employees);

        // Then
        List<Employee> updated = context.queryEmployees()
                .where(Employee::getDepartmentId).eq(1L)
                .list();
        assertThat(updated).hasSize(employees.size());
        for (int i = 0; i < employees.size(); i++) {
            assertThat(updated.get(i).getSalary()).isEqualTo(employees.get(i).getSalary());
        }
    }

    ///
    /// 测试s deleting a single employee.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should delete single employee")
    void shouldDeleteSingleEmployee(IntegrationTestContext context) {
        // Given - create an employee to delete
        Employee newEmployee = createTestEmployee(300L, "To Delete", "delete@example.com");
        context.operations(Employee.class).insert(newEmployee);

        // Verify it exists
        List<Employee> before = context.queryEmployees()
                .where(Employee::getId).eq(300L)
                .list();
        assertThat(before).hasSize(1);

        // When
        context.delete(newEmployee);

        // Then
        List<Employee> after = context.queryEmployees()
                .where(Employee::getId).eq(300L)
                .list();
        assertThat(after).isEmpty();
    }

    ///
    /// 测试s deleting multiple employees.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should delete multiple employees")
    void shouldDeleteMultipleEmployees(IntegrationTestContext context) {
        // Given - create employees to delete
        List<Employee> newEmployees = new ArrayList<>();
        newEmployees.add(createTestEmployee(400L, "Delete 1", "delete1@example.com"));
        newEmployees.add(createTestEmployee(401L, "Delete 2", "delete2@example.com"));
        context.operations(Employee.class).insertAll(newEmployees);

        // Verify they exist
        List<Employee> before = context.queryEmployees()
                .where(Employee::getId).in(400L, 401L)
                .list();
        assertThat(before).hasSize(2);

        // When
        context.deleteAll(newEmployees);

        // Then
        List<Employee> after = context.queryEmployees()
                .where(Employee::getId).in(400L, 401L)
                .list();
        assertThat(after).isEmpty();
    }

    ///
    /// 测试s deleting by ID using where clause.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should delete with where condition")
    void shouldDeleteWithWhereCondition(IntegrationTestContext context) {
        // Given - create an employee to delete
        Employee newEmployee = createTestEmployee(500L, "Condition Delete", "cond@example.com");
        context.operations(Employee.class).insert(newEmployee);

        // When - delete using where clause
        Employee toDelete = context.queryEmployees()
                .where(Employee::getId).eq(500L)
                .list().get(0);
        context.delete(toDelete);

        // Then
        List<Employee> after = context.queryEmployees()
                .where(Employee::getId).eq(500L)
                .list();
        assertThat(after).isEmpty();
    }

    ///
    /// 测试s updating employee status.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should update employee status")
    void shouldUpdateEmployeeStatus(IntegrationTestContext context) {
        // Given
        Employee employee = context.queryEmployees()
                .where(Employee::getId).eq(1L)
                .list().get(0);
        EmployeeStatus originalStatus = employee.getStatus();
        employee.setStatus(EmployeeStatus.INACTIVE);

        // When
        context.operations(Employee.class).update(employee);

        // Then
        Employee updated = context.queryEmployees()
                .where(Employee::getId).eq(1L)
                .list().get(0);
        assertThat(updated.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);
        assertThat(updated.getStatus()).isNotEqualTo(originalStatus);
    }

    ///
    /// 测试s inserting and updating department.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should insert and update department")
    void shouldInsertAndUpdateDepartment(IntegrationTestContext context) {
        // Given - insert new department
        Department newDept = new Department(200L, "Research", "Building F", 300000.0, true);
        context.operations(Department.class).insert(newDept);

        // When - update the department
        Department dept = context.queryDepartments()
                .where(Department::getId).eq(200L)
                .list().get(0);
        dept.setBudget(400000.0);
        dept.setLocation("Building G");
        context.operations(Department.class).update(dept);

        // Then
        Department updated = context.queryDepartments()
                .where(Department::getId).eq(200L)
                .list().get(0);
        assertThat(updated.getBudget()).isEqualTo(400000.0);
        assertThat(updated.getLocation()).isEqualTo("Building G");
    }

    ///
    /// 测试s inserting employee with all fields.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should insert employee with all fields")
    void shouldInsertEmployeeWithAllFields(IntegrationTestContext context) {
        // Given
        Employee employee = new Employee();
        employee.setId(600L);
        employee.setName("Full Employee");
        employee.setEmail("full@example.com");
        employee.setSalary(75000.0);
        employee.setActive(true);
        employee.setStatus(EmployeeStatus.ACTIVE);
        employee.setDepartmentId(1L);
        employee.setHireDate(LocalDate.of(2024, 1, 15));

        // When
        context.operations(Employee.class).insert(employee);

        // Then
        Employee inserted = context.queryEmployees()
                .where(Employee::getId).eq(600L)
                .list().get(0);
        assertThat(inserted.getName()).isEqualTo("Full Employee");
        assertThat(inserted.getSalary()).isEqualTo(75000.0);
        assertThat(inserted.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(inserted.getHireDate()).isEqualTo(LocalDate.of(2024, 1, 15));
    }

    ///
    /// 测试s inserting duplicate ID should fail.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should fail when inserting duplicate ID")
    void shouldFailOnDuplicateId(IntegrationTestContext context) {
        // Given - employee with ID 1 already exists
        Employee duplicateEmployee = createTestEmployee(1L, "Duplicate", "dup@example.com");

        // When/Then - should throw exception
        assertThatThrownBy(() -> context.operations(Employee.class).insert(duplicateEmployee))
                .isInstanceOf(RuntimeException.class);
    }

    /// 测试s updating non-existent employee.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should handle update of non-existent employee")
    void shouldHandleUpdateNonExistent(IntegrationTestContext context) {
        // Given
        Employee nonExistent = createTestEmployee(9999L, "Non Existent", "none@example.com");

        // When/Then - should throw exception (entity not found)
        assertThatThrownBy(() -> context.operations(Employee.class).update(nonExistent))
                .isInstanceOf(RuntimeException.class);

    }

    // 删除不存在的实体：**3.x 无对位，用例已移除**。
    // 2.2.3 这条断言的是 `PersistExecutor.delete(entity, descriptor)` 在实参不存在时抛 `RuntimeException`，
    // 而它自己的注释写的是「should not throw exception」——断言与注释自相矛盾。
    // 3.x 的写入接缝按调用点裁掉了 `delete`，删除由消费方的 Spring Data 承担，这里没有被测对象；
    // 若把断言指向测试装置里的 `delete` 辅助方法，证明的只是辅助方法自己，与库无关。
    // 裁决记录见 `tmp/legacy/ADJUDICATION.md` 第 1 条。

    ///
    /// 测试s inserting employee with null email.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should insert employee with null email")
    void shouldInsertEmployeeWithNullEmail(IntegrationTestContext context) {
        // Given
        Employee employee = createTestEmployee(700L, "No Email", null);

        // When
        context.operations(Employee.class).insert(employee);

        // Then
        Employee inserted = context.queryEmployees()
                .where(Employee::getId).eq(700L)
                .list().get(0);
        assertThat(inserted.getName()).isEqualTo("No Email");
        assertThat(inserted.getEmail()).isNull();
    }

    ///
    /// 测试s batch insert with empty list.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should handle empty batch insert")
    void shouldHandleEmptyBatchInsert(IntegrationTestContext context) {
        // Given
        List<Employee> emptyList = new ArrayList<>();

        // When/Then - should not throw exception
        assertThatNoException().isThrownBy(() -> context.operations(Employee.class).insertAll(emptyList));
    }

    private Employee createTestEmployee(Long id, String name, String email) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setName(name);
        employee.setEmail(email);
        employee.setSalary(50000.0);
        employee.setActive(true);
        employee.setStatus(EmployeeStatus.ACTIVE);
        employee.setDepartmentId(1L);
        employee.setHireDate(LocalDate.now());
        return employee;
    }
}

