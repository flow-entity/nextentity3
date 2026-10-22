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
/// Integration tests for ExpressionBuilder.StringOperator default 方法.
/// <p>
/// 测试s default 方法 in ExpressionBuilder.StringOperator interface including:
/// - IfNotNull series: startsWithIfNotNull, endsWithIfNotNull, containsIfNotNull, etc.
/// - IfNotEmpty series: startsWithIfNotEmpty, endsWithIfNotEmpty, containsIfNotEmpty, etc.
/// <p>
/// These tests run against MySQL and PostgreSQL using 测试containers.
///
/// @author HuangChengwei
/// @see io.github.nextentity.api.ExpressionBuilder.StringOperator
@DisplayName("StringOperator Default Methods Integration Tests")
public class StringOperatorDefaultMethodsIntegrationTest {

    // ==================== IfNotNull Default Methods Tests ====================

    ///
    /// 测试s containsIfNotNull with non-null value in WHERE clause.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with containsIfNotNull in WHERE clause")
    void shouldFilterWithContainsIfNotNullInWhere(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).containsIfNotNull("John")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getName().contains("John"));
    }

    ///
    /// 测试s containsIfNotNull with null value in WHERE clause.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should skip filter when containsIfNotNull is null")
    void shouldSkipFilterWhenContainsIfNotNullIsNull(IntegrationTestContext context) {
        // Given
        long totalCount = context.queryEmployees().count();

        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).containsIfNotNull(null)
                .list();

        // Then
        assertThat(employees).hasSize((int) totalCount);
    }

    // ==================== IfNotEmpty Default Methods Tests ====================

    ///
    /// 测试s containsIfNotEmpty with non-empty value in WHERE clause.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with containsIfNotEmpty in WHERE clause")
    void shouldFilterWithContainsIfNotEmptyInWhere(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).containsIfNotEmpty("John")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getName().contains("John"));
    }

    ///
    /// 测试s containsIfNotEmpty with empty value in WHERE clause.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should skip filter when containsIfNotEmpty is empty")
    void shouldSkipFilterWhenContainsIfNotEmptyIsEmpty(IntegrationTestContext context) {
        // Given
        long totalCount = context.queryEmployees().count();

        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).containsIfNotEmpty("")
                .list();

        // Then
        assertThat(employees).hasSize((int) totalCount);
    }

    // ==================== Combined Conditional Tests ====================

    ///
    /// 测试s getFirst with IfNotNull string condition.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should getFirst with IfNotNull string condition")
    void shouldGetFirstWithIfNotNullStringCondition(IntegrationTestContext context) {
        // When
        Employee employee = context.queryEmployees()
                .where(Employee::getName).containsIfNotNull("Alice")
                .orderBy(Employee::getId).asc()
                .first();

        // Then
        assertThat(employee).isNotNull();
        assertThat(employee.getName()).contains("Alice");
    }

}

