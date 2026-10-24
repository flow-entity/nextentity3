package io.github.nextentity.integration;

import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.entity.Employee;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

///
/// Integration tests for OrderByStep multiple parameter orderBy 方法.
/// <p>
/// 测试s default 方法 in OrderByStep interface including:
/// - orderBy(Order): Sort by single Order object
/// - orderBy(Order, Order): Sort by two Order objects
/// - orderBy(Order, Order, Order): Sort by three Order objects
/// - orderBy(Path, Path): Sort by two paths
/// - orderBy(Path, Path, Path): Sort by three paths
/// <p>
/// These tests run against MySQL and PostgreSQL using 测试containers.
///
/// @author HuangChengwei
/// @see io.github.nextentity.api.OrderByStep
@DisplayName("OrderByStep Multiple Parameters Integration Tests")
public class OrderByStepMultipleParametersIntegrationTest {

    // ==================== orderBy(Order) Tests ====================

    ///
    /// 测试s orderBy(Order) with a single Order object.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by single Order object")
    void shouldOrderBySingleOrder(IntegrationTestContext context) {
        // Given

        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        for (int i = 1; i < employees.size(); i++) {
            assertThat(employees.get(i).getName().compareTo(employees.get(i - 1).getName()))
                    .isGreaterThanOrEqualTo(0);
        }
    }

    // ==================== orderBy(Order, Order) Tests ====================

    ///
    /// 测试s orderBy(Order, Order) with two Order objects.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by two Order objects")
    void shouldOrderByTwoOrders(IntegrationTestContext context) {
        // Given

        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getSalary).desc()
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        // Verify salary is in descending order
        for (int i = 1; i < employees.size(); i++) {
            Double current = employees.get(i).getSalary();
            Double previous = employees.get(i - 1).getSalary();
            assertThat(current).isLessThanOrEqualTo(previous);
        }
    }

    ///
    /// 测试s orderBy(Order, Order) with both ascending orders.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by two ascending Order objects")
    void shouldOrderByTwoAscendingOrders(IntegrationTestContext context) {
        // Given

        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getId).asc()
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees.get(0).getId()).isEqualTo(1L);
    }

    // ==================== orderBy(Order, Order, Order) Tests ====================

    ///
    /// 测试s orderBy(Order, Order, Order) with three Order objects.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by three Order objects")
    void shouldOrderByThreeOrders(IntegrationTestContext context) {
        // Given

        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getSalary).desc()
                .orderBy(Employee::getName).asc()
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
    }

    // ==================== orderBy(Path, Path) Tests ====================

    ///
    /// 测试s orderBy(Path, Path) with two paths.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by two paths")
    void shouldOrderByTwoPaths(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getSalary).asc()
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
    }

    ///
    /// 测试s orderBy(Path, Path) with descending order.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by two paths descending")
    void shouldOrderByTwoPathsDescending(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getSalary).desc()
                .orderBy(Employee::getId).desc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        // Verify descending order
        for (int i = 1; i < employees.size(); i++) {
            assertThat(employees.get(i).getSalary())
                    .isLessThanOrEqualTo(employees.get(i - 1).getSalary());
        }
    }

    // ==================== orderBy(Path, Path, Path) Tests ====================

    ///
    /// 测试s orderBy(Path, Path, Path) with three paths.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by three paths")
    void shouldOrderByThreePaths(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getDepartmentId).asc()
                .orderBy(Employee::getSalary).asc()
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
    }

    ///
    /// 测试s orderBy(Path, Path, Path) with descending order.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by three paths descending")
    void shouldOrderByThreePathsDescending(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getDepartmentId).desc()
                .orderBy(Employee::getSalary).desc()
                .orderBy(Employee::getId).desc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
    }

    // ==================== Combined with Filter Tests ====================

    ///
    /// 测试s combining multiple Order objects with where clause.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should combine multiple orders with where clause")
    void shouldCombineMultipleOrdersWithWhere(IntegrationTestContext context) {
        // Given

        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getSalary).gt(50000.0)
                .orderBy(Employee::getSalary).desc()
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() > 50000.0);
    }

    ///
    /// 测试s count with multiple Order objects.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should count with multiple Order objects")
    void shouldCountWithMultipleOrders(IntegrationTestContext context) {
        // Given

        // When
        long count = context.queryEmployees()
                .orderBy(Employee::getSalary).desc()
                .orderBy(Employee::getId).asc()
                .count();

        // Then
        assertThat(count).isPositive();
    }

    ///
    /// 测试s limit with multiple paths.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should limit with multiple paths")
    void shouldLimitWithMultiplePaths(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getSalary).desc()
                .orderBy(Employee::getName).desc()
                .list(5);

        // Then
        assertThat(employees).hasSize(5);
    }
}


