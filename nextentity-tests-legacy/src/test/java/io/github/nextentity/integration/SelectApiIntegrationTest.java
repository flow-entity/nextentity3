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
/// Select API integration tests.
///
/// 3.x 的投影面只有四种：`select(PathRef)`、`select(Expression)`、`select(Expression, Expression)`（`Tuple2`）、
/// `selectDistinct(PathRef)`——证据 `api/SelectStep.java`。因此只保留能对位的用例；
/// `Tuple3~10`、`select(Collection)`、多列 `selectDistinct`、`select(Class)`、`min`/`avg`
/// 对应的用例已删除（见 `tmp/legacy/NOT-PORTED-port-b.md`）。
///
/// @author HuangChengwei
@DisplayName("Select API Integration Tests")
public class SelectApiIntegrationTest {

    // ========================================
    // 1. Single Path Selection
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select single path - name")
    void shouldSelectSinglePathName(IntegrationTestContext context) {
        // When
        List<String> names = context.queryEmployees()
                .select(Employee::getName)
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(names).hasSize(12);
        assertThat(names.getFirst()).isEqualTo("Alice Johnson");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select single path - salary")
    void shouldSelectSinglePathSalary(IntegrationTestContext context) {
        // When
        List<Double> salaries = context.queryEmployees()
                .select(Employee::getSalary)
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(salaries).hasSize(12);
        assertThat(salaries).allMatch(s -> s != null && s > 0);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select single path with where condition")
    void shouldSelectSinglePathWithWhere(IntegrationTestContext context) {
        // When
        List<String> names = context.queryEmployees()
                .select(Employee::getName)
                .where(Employee::getDepartmentId).eq(1L)
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(names).hasSize(5);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct single path")
    void shouldSelectDistinctSinglePath(IntegrationTestContext context) {
        // When
        List<Long> deptIds = context.queryEmployees()
                .selectDistinct(Employee::getDepartmentId)
                .orderBy(Employee::getDepartmentId).asc()
                .list();

        // Then
        assertThat(deptIds).hasSize(5);
        assertThat(deptIds).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    // ========================================
    // 2. Tuple2 Selection (Two Paths)
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select two paths as Tuple2")
    void shouldSelectTwoPaths(IntegrationTestContext context) {
        // When
        List<Tuple2<String, String>> results = context.queryEmployees()
                .select(Path.of(Employee::getName), Path.of(Employee::getEmail))
                .where(Employee::getId).eq(1L)
                .list();

        // Then
        assertThat(results).hasSize(1);
        Tuple2<String, String> tuple = results.getFirst();
        assertThat(tuple.get0()).isEqualTo("Alice Johnson");
        assertThat(tuple.get1()).isEqualTo("alice@example.com");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select two paths with condition")
    void shouldSelectTwoPathsWithCondition(IntegrationTestContext context) {
        // When
        List<Tuple2<String, Double>> results = context.queryEmployees()
                .select(Path.of(Employee::getName), Path.of(Employee::getSalary))
                .where(Employee::getSalary).gt(70000.0)
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(t -> t.get1() > 70000.0);
    }

    // ========================================
    // 3. TypedExpression Selection（聚合只有 max / count / sum）
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select with typed expression - max")
    void shouldSelectWithTypedExpressionMax(IntegrationTestContext context) {
        // When
        Double maxSalary = context.queryEmployees()
                .select(Path.of(Employee::getSalary).max())
                .single();

        // Then
        assertThat(maxSalary).isNotNull();
        assertThat(maxSalary).isGreaterThan(0);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select with typed expression - count")
    void shouldSelectWithTypedExpressionCount(IntegrationTestContext context) {
        // When
        Long count = context.queryEmployees()
                .select(Path.of(Employee::getId).count())
                .single();

        // Then
        assertThat(count).isEqualTo(12L);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select with typed expression - sum")
    void shouldSelectWithTypedExpressionSum(IntegrationTestContext context) {
        // When
        Double sum = context.queryEmployees()
                .select(Path.of(Employee::getSalary).sum())
                .single();

        // Then
        assertThat(sum).isNotNull();
        assertThat(sum).isGreaterThan(0);
    }

    // ========================================
    // 4. Selection with Conditions and Ordering
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select with where and order by")
    void shouldSelectWithWhereAndOrderBy(IntegrationTestContext context) {
        // When
        List<Tuple2<Long, String>> results = context.queryEmployees()
                .select(Path.of(Employee::getId), Path.of(Employee::getName))
                .where(Employee::getActive).eq(true)
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(results).isNotEmpty();
        // Verify ordering
        for (int i = 0; i < results.size() - 1; i++) {
            String current = results.get(i).get1();
            String next = results.get(i + 1).get1();
            assertThat(current.compareTo(next)).isLessThanOrEqualTo(0);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select with pagination")
    void shouldSelectWithPagination(IntegrationTestContext context) {
        // When
        List<Tuple2<Long, String>> results = context.queryEmployees()
                .select(Path.of(Employee::getId), Path.of(Employee::getName))
                .orderBy(Employee::getId).asc()
                .list(5);

        // Then
        assertThat(results).hasSize(5);
        assertThat(results.getFirst().get0()).isEqualTo(1L);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select with limit")
    void shouldSelectWithLimit(IntegrationTestContext context) {
        // When
        List<Tuple2<Long, String>> results = context.queryEmployees()
                .select(Path.of(Employee::getId), Path.of(Employee::getName))
                .orderBy(Employee::getId).asc()
                .list(3);

        // Then
        assertThat(results).hasSize(3);
    }

    // ========================================
    // 5. Edge Cases
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should handle select with null values in data")
    void shouldHandleSelectWithNullValues(IntegrationTestContext context) {
        // Given - Employee 1 exists
        // When
        List<Tuple2<String, String>> results = context.queryEmployees()
                .select(Path.of(Employee::getName), Path.of(Employee::getEmail))
                .where(Employee::getId).eq(1L)
                .list();

        // Then
        assertThat(results).hasSize(1);
        // Email could be null
        assertThat(results.getFirst().get0()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should return empty list for no matches")
    void shouldReturnEmptyListForNoMatches(IntegrationTestContext context) {
        // When
        List<Tuple2<Long, String>> results = context.queryEmployees()
                .select(Path.of(Employee::getId), Path.of(Employee::getName))
                .where(Employee::getId).eq(99999L)
                .list();

        // Then
        assertThat(results).isEmpty();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select single result with getSingle")
    void shouldSelectSingleWithGetSingle(IntegrationTestContext context) {
        // When
        Tuple2<Long, String> result = context.queryEmployees()
                .select(Path.of(Employee::getId), Path.of(Employee::getName))
                .where(Employee::getId).eq(1L)
                .single();

        // Then
        assertThat(result).isNotNull();
        assertThat(result.get0()).isEqualTo(1L);
        assertThat(result.get1()).isEqualTo("Alice Johnson");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select first result with first")
    void shouldSelectFirst(IntegrationTestContext context) {
        // When
        var result = context.queryEmployees()
                .select(Path.of(Employee::getId), Path.of(Employee::getName))
                .orderBy(Employee::getId).asc()
                .first();

        // Then
        assertThat(result).isNotNull();
        assertThat(result.get0()).isEqualTo(1L);
    }
}
