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
/// Integration tests for StringExpression default 方法.
/// <p>
/// 测试s default 方法 in StringExpression interface including:
/// - endsWith(String): Match patterns ending with specified string
/// - contains(String): Match patterns containing specified string
/// - notStartsWith(String): Does not match patterns starting with specified string
/// - notEndsWith(String): Does not match patterns ending with specified string
/// - notContains(String): Does not match patterns containing specified string
/// - IfNotNull variants: Conditional string matching when value is not null
/// - IfNotEmpty variants: Conditional string matching when value is not empty
/// - substring(int): Substring from offset to end
/// <p>
/// These tests run against MySQL and PostgreSQL using 测试containers.
///
/// @author HuangChengwei
/// @see io.github.nextentity.api.StringExpression
@DisplayName("StringExpression Default Methods Integration Tests")
public class StringExpressionDefaultMethodsIntegrationTest {

    // ==================== endsWith Tests ====================

    // ==================== contains Tests ====================

    ///
    /// 测试s contains(String) - matches patterns containing specified string.
    /// This is a default 方法 that calls like('%' + value + '%').
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with contains in WHERE clause")
    void shouldFilterWithContains(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).contains("John")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getName().contains("John"));
    }

    // ==================== notStartsWith Tests ====================

    // ==================== notEndsWith Tests ====================

    // ==================== notContains Tests ====================

    // ==================== startsWithIfNotNull Tests ====================

    // ==================== endsWithIfNotNull Tests ====================

    // ==================== containsIfNotNull Tests ====================

    ///
    /// 测试s containsIfNotNull with non-null value.
    /// Covers the branch: value != null.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with containsIfNotNull when value is not null")
    void shouldFilterWithContainsIfNotNullNonNull(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).containsIfNotNull("John")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getName().contains("John"));
    }

    ///
    /// 测试s containsIfNotNull with null value.
    /// Covers the branch: value == null.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should skip filter when containsIfNotNull is null")
    void shouldSkipFilterWhenContainsIfNotNullNull(IntegrationTestContext context) {
        // Given
        long totalCount = context.queryEmployees().count();

        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).containsIfNotNull(null)
                .list();

        // Then
        assertThat(employees).hasSize((int) totalCount);
    }

    // ==================== notStartsWithIfNotNull Tests ====================

    // ==================== notEndsWithIfNotNull Tests ====================

    // ==================== notContainsIfNotNull Tests ====================

    // ==================== startsWithIfNotEmpty Tests ====================

    // ==================== endsWithIfNotEmpty Tests ====================

    // ==================== containsIfNotEmpty Tests ====================

    ///
    /// 测试s containsIfNotEmpty with non-empty value.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with containsIfNotEmpty when value is not empty")
    void shouldFilterWithContainsIfNotEmptyNonEmpty(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).containsIfNotEmpty("John")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getName().contains("John"));
    }

    ///
    /// 测试s containsIfNotEmpty with empty value.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should skip filter when containsIfNotEmpty is empty")
    void shouldSkipFilterWhenContainsIfNotEmptyEmpty(IntegrationTestContext context) {
        // Given
        long totalCount = context.queryEmployees().count();

        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).containsIfNotEmpty("")
                .list();

        // Then
        assertThat(employees).hasSize((int) totalCount);
    }

    // ==================== notStartsWithIfNotEmpty Tests ====================

    // ==================== notEndsWithIfNotEmpty Tests ====================

    // ==================== notContainsIfNotEmpty Tests ====================

    // ==================== Combined Conditional Tests ====================

    // ==================== Count and Exist Tests ====================

    ///
    /// 测试s exist with string expression condition.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should check exist with string expression condition")
    void shouldCheckExistWithStringExpressionCondition(IntegrationTestContext context) {
        // When
        boolean exists = context.queryEmployees()
                .where(Employee::getName).contains("John")
                .exists();

        // Then
        assertThat(exists).isTrue();
    }

}


