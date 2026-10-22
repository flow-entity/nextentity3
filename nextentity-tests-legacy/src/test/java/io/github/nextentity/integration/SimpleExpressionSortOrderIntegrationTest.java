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
/// Integration tests for SimpleExpression asc() and desc() default 方法.
/// <p>
/// 测试s default 方法 in SimpleExpression interface including:
/// - asc(): Returns Order for ascending sort
/// - desc(): Returns Order for descending sort
/// <p>
/// These tests verify that the Order objects returned by asc()/desc()
/// can be used with orderBy(Order) 方法.
/// <p>
/// These tests run against MySQL and PostgreSQL using 测试containers.
///
/// @author HuangChengwei
/// @see io.github.nextentity.api.SimpleExpression
@DisplayName("SimpleExpression Sort Order Integration Tests")
public class SimpleExpressionSortOrderIntegrationTest {

    // ==================== asc() Tests ====================

    ///
    /// 测试s asc() 方法 on string field.
    /// The asc() 方法 returns an Order object that can be used with orderBy(Order).
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by string field ascending using asc()")
    void shouldOrderByStringAsc(IntegrationTestContext context) {
        // Given - use asc() to create Order from SimpleExpression

        // When - pass Order to orderBy
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
    /// 测试s asc() 方法 on numeric field.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by numeric field ascending using asc()")
    void shouldOrderByNumericAsc(IntegrationTestContext context) {
        // Given

        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getSalary).asc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        for (int i = 1; i < employees.size(); i++) {
            assertThat(employees.get(i).getSalary())
                    .isGreaterThanOrEqualTo(employees.get(i - 1).getSalary());
        }
    }

    ///
    /// 测试s asc() 方法 on ID field.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by ID field ascending using asc()")
    void shouldOrderByIdAsc(IntegrationTestContext context) {
        // Given

        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        for (int i = 1; i < employees.size(); i++) {
            assertThat(employees.get(i).getId())
                    .isGreaterThan(employees.get(i - 1).getId());
        }
    }

    // ==================== desc() Tests ====================

    ///
    /// 测试s desc() 方法 on string field.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by string field descending using desc()")
    void shouldOrderByStringDesc(IntegrationTestContext context) {
        // Given

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
    /// 测试s desc() 方法 on numeric field.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by numeric field descending using desc()")
    void shouldOrderByNumericDesc(IntegrationTestContext context) {
        // Given

        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getSalary).desc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        for (int i = 1; i < employees.size(); i++) {
            assertThat(employees.get(i).getSalary())
                    .isLessThanOrEqualTo(employees.get(i - 1).getSalary());
        }
    }

    ///
    /// 测试s desc() 方法 on ID field.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by ID field descending using desc()")
    void shouldOrderByIdDesc(IntegrationTestContext context) {
        // Given

        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getId).desc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        for (int i = 1; i < employees.size(); i++) {
            assertThat(employees.get(i).getId())
                    .isLessThan(employees.get(i - 1).getId());
        }
    }

    // ==================== Combined with Filter Tests ====================

    ///
    /// 测试s asc() combined with where clause.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should combine asc() with where clause")
    void shouldCombineAscWithWhere(IntegrationTestContext context) {
        // Given

        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getSalary).gt(50000.0)
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() > 50000.0);
        for (int i = 1; i < employees.size(); i++) {
            assertThat(employees.get(i).getName().compareTo(employees.get(i - 1).getName()))
                    .isGreaterThanOrEqualTo(0);
        }
    }

    ///
    /// 测试s desc() combined with where clause.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should combine desc() with where clause")
    void shouldCombineDescWithWhere(IntegrationTestContext context) {
        // Given

        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getSalary).gt(50000.0)
                .orderBy(Employee::getSalary).desc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() > 50000.0);
        for (int i = 1; i < employees.size(); i++) {
            assertThat(employees.get(i).getSalary())
                    .isLessThanOrEqualTo(employees.get(i - 1).getSalary());
        }
    }

    // ==================== Count and Exist Tests ====================

    ///
    /// 测试s count with asc() ordering.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should count with asc() ordering")
    void shouldCountWithAscOrdering(IntegrationTestContext context) {
        // Given

        // When
        long count = context.queryEmployees()
                .orderBy(Employee::getId).asc()
                .count();

        // Then
        assertThat(count).isPositive();
    }

    ///
    /// 测试s exist with desc() ordering.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should check exist with desc() ordering")
    void shouldCheckExistWithDescOrdering(IntegrationTestContext context) {
        // Given

        // When
        boolean exists = context.queryEmployees()
                .orderBy(Employee::getId).desc()
                .exists();

        // Then
        assertThat(exists).isTrue();
    }

    // ==================== GetFirst/GetSingle Tests ====================

    ///
    /// 测试s getFirst with asc() ordering.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should getFirst with asc() ordering returns minimum")
    void shouldGetFirstWithAscReturnsMinimum(IntegrationTestContext context) {
        // Given

        // When
        Employee first = context.queryEmployees()
                .orderBy(Employee::getId).asc()
                .first();

        // Then
        assertThat(first).isNotNull();
        assertThat(first.getId()).isEqualTo(1L);
    }

    ///
    /// 测试s getFirst with desc() ordering.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should getFirst with desc() ordering returns maximum")
    void shouldGetFirstWithDescReturnsMaximum(IntegrationTestContext context) {
        // Given

        // And - get max id for verification
        long maxId = context.queryEmployees()
                .select(Employee::getId)
                .orderBy(Employee::getId).desc()
                .first();

        // When
        Employee first = context.queryEmployees()
                .orderBy(Employee::getId).desc()
                .first();

        // Then
        assertThat(first).isNotNull();
        assertThat(first.getId()).isEqualTo(maxId);
    }

    ///
    /// 测试s limit with asc() ordering.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should limit with asc() ordering")
    void shouldLimitWithAscOrdering(IntegrationTestContext context) {
        // Given

        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getId).asc()
                .list(5);

        // Then
        assertThat(employees).hasSize(5);
        assertThat(employees.getFirst().getId()).isEqualTo(1L);
    }

    ///
    /// 测试s offset with desc() ordering.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should offset with desc() ordering")
    void shouldOffsetWithDescOrdering(IntegrationTestContext context) {
        // Given
        long totalCount = context.queryEmployees().count();

        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getId).desc()
                .slice(2, 10).data();

        // Then
        assertThat(employees).hasSize((int) totalCount - 2);
    }

    // ==================== Multiple Order Tests ====================

}


