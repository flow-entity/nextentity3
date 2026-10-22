package io.github.nextentity.integration;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.entity.Employee;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

///
/// Numeric 操作s integration tests.
/// <p>
/// 测试s numeric 操作s including:
/// - Numeric comparisons (gt, ge, lt, le, between)
/// - Arithmetic 操作s (add, subtract, multiply, divide, mod)
/// - Numeric sorting
/// <p>
/// These tests run against MySQL and PostgreSQL using 测试containers.
///
/// @author HuangChengwei
@DisplayName("Numeric Operations Integration Tests")
public class NumericOperationsIntegrationTest {

    private static final double SALARY_THRESHOLD = 60000.0;

    ///
    /// 测试s greater than comparison.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with greater than")
    void shouldFilterWithGreaterThan(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getSalary).gt(SALARY_THRESHOLD)
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() > SALARY_THRESHOLD);
    }

    ///
    /// 测试s greater than or equal comparison.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with greater than or equal")
    void shouldFilterWithGreaterThanOrEqual(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getSalary).ge(SALARY_THRESHOLD)
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() >= SALARY_THRESHOLD);
    }

    ///
    /// 测试s less than comparison.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with less than")
    void shouldFilterWithLessThan(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getSalary).lt(SALARY_THRESHOLD)
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() < SALARY_THRESHOLD);
    }

    ///
    /// 测试s less than or equal comparison.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with less than or equal")
    void shouldFilterWithLessThanOrEqual(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getSalary).le(SALARY_THRESHOLD)
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() <= SALARY_THRESHOLD);
    }

    ///
    /// 测试s between comparison.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with between")
    void shouldFilterWithBetween(IntegrationTestContext context) {
        // Given
        double minSalary = 55000.0;
        double maxSalary = 75000.0;

        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getSalary).between(minSalary, maxSalary)
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e ->
                e.getSalary() >= minSalary && e.getSalary() <= maxSalary);
    }

    ///
    /// 测试s between with boundary values.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should handle between with boundary values")
    void shouldHandleBetweenBoundary(IntegrationTestContext context) {
        // Given
        double minSalary = 60000.0;
        double maxSalary = 70000.0;

        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getSalary).between(minSalary, maxSalary)
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e ->
                e.getSalary() >= minSalary && e.getSalary() <= maxSalary);
    }

    ///
    /// 测试s numeric IN clause.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with numeric IN clause")
    void shouldFilterWithNumericIn(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getDepartmentId).in(1L, 2L, 3L)
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e ->
                e.getDepartmentId() == 1L || e.getDepartmentId() == 2L || e.getDepartmentId() == 3L);
    }

    ///
    /// 测试s numeric NOT IN clause.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with numeric NOT IN clause")
    void shouldFilterWithNumericNotIn(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getDepartmentId).notIn(1L, 2L)
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getDepartmentId() != 1L && e.getDepartmentId() != 2L);
    }

    ///
    /// 测试s ordering by numeric field ascending.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by numeric field ascending")
    void shouldOrderByNumericAsc(IntegrationTestContext context) {
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
    /// 测试s ordering by numeric field descending.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by numeric field descending")
    void shouldOrderByNumericDesc(IntegrationTestContext context) {
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
    /// 测试s ordering by ID.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by ID ascending")
    void shouldOrderByIdAsc(IntegrationTestContext context) {
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

    ///
    /// 测试s multiple numeric conditions.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with multiple numeric conditions")
    void shouldFilterWithMultipleNumericConditions(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getSalary).ge(50000.0)
                .where(Employee::getSalary).le(80000.0)
                .where(Employee::getDepartmentId).eq(1L)
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e ->
                e.getSalary() >= 50000.0 && e.getSalary() <= 80000.0 && e.getDepartmentId() == 1L);
    }

    ///
    /// 测试s selecting numeric field.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select numeric field")
    void shouldSelectNumericField(IntegrationTestContext context) {
        // When
        List<Double> salaries = context.queryEmployees()
                .select(Employee::getSalary)
                .orderBy(Employee::getSalary).desc()
                .list();

        // Then
        assertThat(salaries).isNotEmpty();
        assertThat(salaries).doesNotContainNull();
    }

    ///
    /// 测试s selecting ID field.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select ID field")
    void shouldSelectIdField(IntegrationTestContext context) {
        // When
        List<Long> ids = context.queryEmployees()
                .select(Employee::getId)
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(ids).isNotEmpty();
        assertThat(ids).doesNotContainNull();
    }

    ///
    /// 测试s SUM aggregation on numeric field.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should sum numeric field")
    void shouldSumNumericField(IntegrationTestContext context) {
        // When
        Number sum = context.queryEmployees()
                .select(Path.of(Employee::getSalary).sum())
                .single();

        // Then
        assertThat(sum).isNotNull();
        assertThat(sum.doubleValue()).isPositive();
    }

    ///
    /// 测试s MAX aggregation on numeric field.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should find max of numeric field")
    void shouldFindMaxNumericField(IntegrationTestContext context) {
        // When
        Number max = context.queryEmployees()
                .select(Path.of(Employee::getSalary).max())
                .single();

        // Then
        assertThat(max).isNotNull();
        // Verify it's actually the max
        List<Employee> employees = context.queryEmployees().list();
        double expectedMax = employees.stream()
                .mapToDouble(Employee::getSalary)
                .max()
                .orElse(0);
        assertThat(max.doubleValue()).isEqualTo(expectedMax);
    }

    ///
    /// 测试s numeric comparison with ID field.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter with ID comparison")
    void shouldFilterWithIdComparison(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getId).gt(5L)
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getId() > 5L);
    }

    ///
    /// 测试s selecting multiple numeric fields.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select multiple numeric fields")
    void shouldSelectMultipleNumericFields(IntegrationTestContext context) {
        // When
        List<Tuple2<Long, Double>> results = context.queryEmployees()
                .select(Path.of(Employee::getId), Path.of(Employee::getSalary))
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(t -> t.get0() != null && t.get1() != null);
    }

    ///
    /// 测试s distinct numeric values.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct numeric values")
    void shouldSelectDistinctNumeric(IntegrationTestContext context) {
        // When
        List<Long> deptIds = context.queryEmployees()
                .selectDistinct(Employee::getDepartmentId)
                .orderBy(Employee::getDepartmentId).asc()
                .list();

        // Then
        assertThat(deptIds).isNotEmpty();
        assertThat(deptIds).doesNotHaveDuplicates();
    }

    ///
    /// 测试s count of numeric filtered results.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should count numeric filtered results")
    void shouldCountNumericFiltered(IntegrationTestContext context) {
        // When
        long count = context.queryEmployees()
                .where(Employee::getSalary).gt(SALARY_THRESHOLD)
                .count();

        // Then
        assertThat(count).isPositive();
    }

    ///
    /// 测试s numeric comparison with exist.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should check existence with numeric filter")
    void shouldCheckExistenceWithNumericFilter(IntegrationTestContext context) {
        // When
        boolean exists = context.queryEmployees()
                .where(Employee::getSalary).gt(100000.0)
                .exists();

        // Then - depends on test data
        // Just verify it returns a boolean
        assertThat(exists).isFalse();
    }

    ///
    /// 测试s ordering by multiple numeric fields.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by multiple numeric fields")
    void shouldOrderByMultipleNumericFields(IntegrationTestContext context) {
        // When
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getDepartmentId).asc()
                .orderBy(Employee::getSalary).desc()
                .list();

        // Then
        assertThat(employees).isNotEmpty();

        Long prevDeptId = null;
        Double prevSalary = null;
        for (Employee emp : employees) {
            if (prevDeptId != null && prevDeptId.equals(emp.getDepartmentId())) {
                assertThat(emp.getSalary()).isLessThanOrEqualTo(prevSalary);
            }
            prevDeptId = emp.getDepartmentId();
            prevSalary = emp.getSalary();
        }
    }

    ///
    /// 测试s numeric 操作s with first result.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should get first with numeric filter")
    void shouldGetFirstWithNumericFilter(IntegrationTestContext context) {
        // When
        Employee employee = context.queryEmployees()
                .where(Employee::getSalary).gt(SALARY_THRESHOLD)
                .orderBy(Employee::getSalary).desc()
                .first();

        // Then
        assertThat(employee).isNotNull();
        assertThat(employee.getSalary()).isGreaterThan(SALARY_THRESHOLD);
    }

    // ==================== Arithmetic Operations ====================

}


