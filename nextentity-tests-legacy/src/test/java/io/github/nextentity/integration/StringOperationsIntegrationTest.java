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
/// String 操作s integration tests.
/// <p>
/// 测试s string 操作s including:
/// - LIKE pattern matching
/// - String functions (lower, upper, trim, substring, length)
/// - String sorting
/// <p>
/// These tests run against MySQL and PostgreSQL using 测试containers.
///
/// @author HuangChengwei
@DisplayName("String Operations Integration Tests")
public class StringOperationsIntegrationTest {

    ///
    /// 测试s LIKE with prefix pattern.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with LIKE prefix pattern")
    void shouldFilterWithLikePrefix(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).likeIfNotNull("A%")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getName().startsWith("A"));
    }

    ///
    /// 测试s LIKE with suffix pattern.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with LIKE suffix pattern")
    void shouldFilterWithLikeSuffix(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getEmail).likeIfNotNull("%@example.com")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getEmail().endsWith("@example.com"));
    }

    ///
    /// 测试s LIKE with contains pattern.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with LIKE contains pattern")
    void shouldFilterWithLikeContains(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).likeIfNotNull("%son%")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getName().toLowerCase().contains("son"));
    }

    ///
    /// 测试s contains 方法.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with contains")
    void shouldFilterWithContains(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).contains("John")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getName().contains("John"));
    }

    ///
    /// 测试s likeIfNotNull with non-null value.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with likeIfNotNull when value is not null")
    void shouldFilterWithLikeIfNotNull(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).likeIfNotNull("A%")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getName().startsWith("A"));
    }

    ///
    /// 测试s likeIfNotNull with null value.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should return all when likeIfNotNull is null")
    void shouldReturnAllWhenLikeIfNotNullIsNull(IntegrationTestContext context) {
        // Given
        long totalCount = context.queryEmployees().count();

        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).likeIfNotNull(null)
                .list();

        // Then
        assertThat(employees).hasSize((int) totalCount);
    }

    ///
    /// 测试s multiple LIKE conditions.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with multiple LIKE conditions")
    void shouldFilterWithMultipleLikeConditions(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).likeIfNotNull("A%")
                .where(Employee::getEmail).likeIfNotNull("%@example.com")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e ->
                e.getName().startsWith("A") && e.getEmail().endsWith("@example.com"));
    }

    ///
    /// 测试s LIKE with special characters.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with LIKE containing special characters")
    void shouldFilterWithLikeSpecialChars(IntegrationTestContext context) {
        // When - email contains @
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getEmail).likeIfNotNull("%@%")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getEmail().contains("@"));
    }

    ///
    /// 测试s ordering by string field ascending.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by string field ascending")
    void shouldOrderByStringAsc(IntegrationTestContext context) {
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

    ///
    /// 测试s ordering by string field descending.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by string field descending")
    void shouldOrderByStringDesc(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getName).desc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        for (int i = 1; i < employees.size(); i++) {
            assertThat(employees.get(i).getName().compareTo(employees.get(i - 1).getName()))
                    .isLessThanOrEqualTo(0);
        }
    }

    ///
    /// 测试s LIKE with underscore wildcard.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should handle LIKE with underscore wildcard")
    void shouldHandleLikeWithUnderscore(IntegrationTestContext context) {
        // When - any single character followed by 'lice'
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).likeIfNotNull("_lice%")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        // Alice should match
        assertThat(employees).anyMatch(e -> e.getName().contains("lice"));
    }

    ///
    /// 测试s combined string 操作s.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should combine multiple string operations")
    void shouldCombineStringOperations(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).likeIfNotNull("A%")
                .where(Employee::getEmail).contains("@")
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e ->
                e.getName().startsWith("A") && e.getEmail().contains("@"));
    }

    ///
    /// 测试s LIKE with no matches.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should return empty when LIKE has no matches")
    void shouldReturnEmptyWhenNoMatches(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).likeIfNotNull("ZZZZZZ%")
                .list();

        // Then
        assertThat(employees).isEmpty();
    }

    ///
    /// 测试s selecting string field.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select string field")
    void shouldSelectStringField(IntegrationTestContext context) {
        // When
        List<String> names = context.queryEmployees()
                .select(Employee::getName)
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(names).isNotEmpty();
        assertThat(names).doesNotContainNull();
    }

    ///
    /// 测试s selecting distinct string values.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct string values")
    void shouldSelectDistinctString(IntegrationTestContext context) {
        // When
        List<String> emails = context.queryEmployees()
                .distinct().select(Employee::getEmail)
                .list();

        // Then
        assertThat(emails).isNotEmpty();
        assertThat(emails).hasSize((int) context.queryEmployees().count());
    }

    // ==================== IfNotNull Default Methods Tests ====================

    ///
    /// 测试s containsIfNotNull with non-null value.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with containsIfNotNull when value is not null")
    void shouldFilterWithContainsIfNotNull(IntegrationTestContext context) {
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
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should return all when containsIfNotNull is null")
    void shouldReturnAllWhenContainsIfNotNullIsNull(IntegrationTestContext context) {
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
    /// 测试s containsIfNotEmpty with non-empty value.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with containsIfNotEmpty when value is not empty")
    void shouldFilterWithContainsIfNotEmpty(IntegrationTestContext context) {
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
    @DisplayName("Should return all when containsIfNotEmpty is empty")
    void shouldReturnAllWhenContainsIfNotEmptyIsEmpty(IntegrationTestContext context) {
        // Given
        long totalCount = context.queryEmployees().count();

        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).containsIfNotEmpty("")
                .list();

        // Then
        assertThat(employees).hasSize((int) totalCount);
    }

    // ==================== Combined Conditional String Tests ====================

}

