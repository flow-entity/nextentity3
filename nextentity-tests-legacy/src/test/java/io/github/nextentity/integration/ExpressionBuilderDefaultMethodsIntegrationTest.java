package io.github.nextentity.integration;

import io.github.nextentity.api.Path;
import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.entity.Employee;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

///
/// Integration tests for the `IfNotNull` operators.
///
/// 2.2.3 的 `ExpressionBuilder.StringOperator` / `NumberOperator` 在 3.x 已删除：算术算子改挂在数值路径上
/// （`api/NumberPath` 继承 `api/NumberArithmetic`），但没有 `*IfNotNull` 变体，`null` 短路不可表达，
/// 因此只移植非空值分支与链式/组合用例；字符串侧可对位的只有 `StringOps.likeIfNotNull`。
/// `*WithNullValue` 与 `substring` / `length` / `likeIfNotEmpty` 一类删除（见 `porting/NOT-PORTED-port-b.md`）。
///
/// @author HuangChengwei
@DisplayName("ExpressionBuilder Default Methods Integration Tests")
public class ExpressionBuilderDefaultMethodsIntegrationTest {

    // ==================== StringOps.likeIfNotNull Tests ====================

    ///
    /// 测试s likeIfNotNull(String) with a non-null value.
    /// This should execute the LIKE 操作.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should execute likeIfNotNull with non-empty value")
    void shouldExecuteLikeIfNotNullWithNonEmptyValue(IntegrationTestContext context) {
        // When - likeIfNotNull with non-empty value
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).likeIfNotNull("Ali%")
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getName().startsWith("Ali"));
    }

    ///
    /// 测试s likeIfNotNull(String) with null value.
    /// This should skip the LIKE 操作 and return all records.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should skip likeIfNotNull with null value")
    void shouldSkipLikeIfNotNullWithNullValue(IntegrationTestContext context) {
        // Given - get total count
        long totalCount = context.queryEmployees().count();

        // When - likeIfNotNull with null value (should be skipped)
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).likeIfNotNull(null)
                .list();

        // Then - should return all records (filter was skipped)
        assertThat(employees).hasSize((int) totalCount);
    }

    // ==================== NumberOperator.addIfNotNull Tests ====================

    ///
    /// 测试s addIfNotNull(Number) with a non-null value.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should execute addIfNotNull with non-null value")
    void shouldExecuteAddIfNotNullWithNonNullValue(IntegrationTestContext context) {
        // When - addIfNotNull with non-null value
        // Find employees where (salary + 10000) > 90000, i.e., salary > 80000
        List<Employee> employees = context.queryEmployees()
                .where(Path.of(Employee::getSalary).add(10000.0).gt(90000.0))
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() > 80000.0);
    }

    // ==================== NumberOperator.subtractIfNotNull Tests ====================

    ///
    /// 测试s subtractIfNotNull(Number) with a non-null value.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should execute subtractIfNotNull with non-null value")
    void shouldExecuteSubtractIfNotNullWithNonNullValue(IntegrationTestContext context) {
        // When - subtractIfNotNull with non-null value
        // Find employees where (salary - 10000) > 60000, i.e., salary > 70000
        List<Employee> employees = context.queryEmployees()
                .where(Path.of(Employee::getSalary).sub(10000.0).gt(60000.0))
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() > 70000.0);
    }

    // ==================== NumberOperator.multiplyIfNotNull Tests ====================

    ///
    /// 测试s multiplyIfNotNull(Number) with a non-null value.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should execute multiplyIfNotNull with non-null value")
    void shouldExecuteMultiplyIfNotNullWithNonNullValue(IntegrationTestContext context) {
        // When - multiplyIfNotNull with non-null value
        // Find employees where (salary * 0.1) > 7000, i.e., salary > 70000
        List<Employee> employees = context.queryEmployees()
                .where(Path.of(Employee::getSalary).mul(0.1).gt(7000.0))
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() > 70000.0);
    }

    // ==================== NumberOperator.divideIfNotNull Tests ====================

    ///
    /// 测试s divideIfNotNull(Number) with a non-null value.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should execute divideIfNotNull with non-null value")
    void shouldExecuteDivideIfNotNullWithNonNullValue(IntegrationTestContext context) {
        // When - divideIfNotNull with non-null value
        // Find employees where (salary / 1000) > 75, i.e., salary > 75000
        List<Employee> employees = context.queryEmployees()
                .where(Path.of(Employee::getSalary).div(1000.0).gt(75.0))
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() > 75000.0);
    }

    // ==================== NumberOperator.modIfNotNull Tests ====================

    ///
    /// 测试s modIfNotNull(Number) with a non-null value.
    /// Note: This test is primarily for coverage of the default 方法.
    /// The actual SQL execution may vary by database.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should execute modIfNotNull with non-null value")
    void shouldExecuteModIfNotNullWithNonNullValue(IntegrationTestContext context) {
        // When - modIfNotNull with non-null value using integer division
        // Use mod with integer value to find employees with even salary thousands
        List<Employee> employees = context.queryEmployees()
                .where(Path.of(Employee::getId).mod(2L).eq(0L))
                .list(5);

        // Then - should find employees with even IDs
        assertThat(employees).isNotEmpty();
    }

    // ==================== Chaining Tests ====================

    ///
    /// 测试s chaining multiple numeric 操作s with IfNotNull variants.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should chain multiple numeric operations with IfNotNull")
    void shouldChainMultipleNumericOperations(IntegrationTestContext context) {
        // When - chain addIfNotNull and subtractIfNotNull
        // (salary + 5000 - 5000) > 70000, i.e., salary > 70000
        List<Employee> employees = context.queryEmployees()
                .where(Path.of(Employee::getSalary)
                        .add(5000.0)
                        .sub(5000.0)
                        .gt(70000.0))
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() > 70000.0);
    }

    // ==================== Combined Operations ====================

    ///
    /// 测试s combining numeric 操作s with like predicate.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should combine numeric operations with like predicate")
    void shouldCombineNumericOperationsWithLikePredicate(IntegrationTestContext context) {
        // When - use numeric operations with name filter
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).like("A%")
                .where(Path.of(Employee::getSalary).add(10000.0).gt(80000.0))
                .list();

        // Then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getName().startsWith("A") && e.getSalary() > 70000.0);
    }

    ///
    /// 测试s all IfNotNull variants in a single complex query.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should use all numeric IfNotNull variants")
    void shouldUseAllNumericIfNotNullVariants(IntegrationTestContext context) {
        // When - use all numeric IfNotNull variants
        // This tests all 5 methods: add, subtract, multiply, divide, mod
        List<Employee> addEmployees = context.queryEmployees()
                .where(Path.of(Employee::getSalary).add(1000.0).gt(60000.0)).list();

        List<Employee> subtractEmployees = context.queryEmployees()
                .where(Path.of(Employee::getSalary).sub(1000.0).gt(60000.0)).list();

        List<Employee> multiplyEmployees = context.queryEmployees()
                .where(Path.of(Employee::getSalary).mul(0.5).gt(30000.0)).list();

        List<Employee> divideEmployees = context.queryEmployees()
                .where(Path.of(Employee::getSalary).div(2.0).gt(30000.0)).list();

        // Use ID for mod test since salary mod may have issues with some databases
        List<Employee> modEmployees = context.queryEmployees()
                .where(Path.of(Employee::getId).mod(2L).eq(0L)).list();

        // Then - all should return results
        assertThat(addEmployees).isNotEmpty();
        assertThat(subtractEmployees).isNotEmpty();
        assertThat(multiplyEmployees).isNotEmpty();
        assertThat(divideEmployees).isNotEmpty();
        assertThat(modEmployees).isNotEmpty();
    }
}
