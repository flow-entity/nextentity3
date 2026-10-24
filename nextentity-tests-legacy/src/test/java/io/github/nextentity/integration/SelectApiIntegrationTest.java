package io.github.nextentity.integration;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMappers;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
import io.github.nextentity.api.model.Tuple4;
import io.github.nextentity.api.model.Tuple5;
import io.github.nextentity.api.model.Tuple6;
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
/// 3.x 投影面：单列 / 两列 `Tuple2` / 三列 `Tuple3` / 四~六列 `ProjectionMappers` / `distinct()`（含多列）。
/// 七列以上、`select(Collection)`、`select(Class)` 无对位，对应用例删除（见 `porting/NOT-PORTED-port-b.md`）。
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
                .distinct().select(Employee::getDepartmentId)
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
    @DisplayName("Should select distinct two paths")
    void shouldSelectDistinctTwoPaths(IntegrationTestContext context) {
        // When
        List<Tuple2<Long, Boolean>> results = context.queryEmployees()
                .distinct().select(Path.of(Employee::getDepartmentId), Path.of(Employee::getActive))
                .orderBy(Employee::getDepartmentId).asc()
                .list();

        // Then
        assertThat(results).isNotEmpty();
        // Verify distinctness
        long distinctCount = results.stream().distinct().count();
        assertThat(results.size()).isEqualTo((int) distinctCount);
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
    // 3. Tuple3 Selection (Three Paths)
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select three paths as Tuple3")
    void shouldSelectThreePaths(IntegrationTestContext context) {
        // When
        List<Tuple3<String, String, Double>> results = context.queryEmployees()
                .select(Path.of(Employee::getName), Path.of(Employee::getEmail), Path.of(Employee::getSalary))
                .where(Employee::getId).eq(1L)
                .list();

        // Then
        assertThat(results).hasSize(1);
        Tuple3<String, String, Double> tuple = results.getFirst();
        assertThat(tuple.get0()).isEqualTo("Alice Johnson");
        assertThat(tuple.get1()).isEqualTo("alice@example.com");
        assertThat(tuple.get2()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct three paths")
    void shouldSelectDistinctThreePaths(IntegrationTestContext context) {
        // When
        List<Tuple3<Long, Boolean, Double>> results = context.queryEmployees()
                .distinct().select(Path.of(Employee::getDepartmentId), Path.of(Employee::getActive),
                        Path.of(Employee::getSalary))
                .list();

        // Then
        assertThat(results).isNotEmpty();
    }

    // ========================================
    // 4. Tuple4 Selection (Four Paths)
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select four paths as Tuple4")
    void shouldSelectFourPaths(IntegrationTestContext context) {
        // When
        List<Tuple4<Long, String, String, Double>> results = context.queryEmployees()
                .project(ProjectionMappers.select(
                        Path.of(Employee::getId),
                        Path.of(Employee::getName),
                        Path.of(Employee::getEmail),
                        Path.of(Employee::getSalary)))
                .where(Employee::getId).eq(1L)
                .list();

        // Then
        assertThat(results).hasSize(1);
        Tuple4<Long, String, String, Double> tuple = results.getFirst();
        assertThat(tuple.get0()).isEqualTo(1L);
        assertThat(tuple.get1()).isEqualTo("Alice Johnson");
        assertThat(tuple.get2()).isEqualTo("alice@example.com");
        assertThat(tuple.get3()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct four paths")
    void shouldSelectDistinctFourPaths(IntegrationTestContext context) {
        // When
        List<Tuple4<Long, Boolean, Double, String>> results = context.queryEmployees()
                .distinct().project(ProjectionMappers.select(
                        Path.of(Employee::getDepartmentId),
                        Path.of(Employee::getActive),
                        Path.of(Employee::getSalary),
                        Path.of(Employee::getName)))
                .list();

        // Then
        assertThat(results).hasSize(12); // All names are distinct
    }

    // ========================================
    // 5. Tuple5 Selection (Five Paths)
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select five paths as Tuple5")
    void shouldSelectFivePaths(IntegrationTestContext context) {
        // When
        List<Tuple5<Long, String, String, Double, Boolean>> results = context.queryEmployees()
                .project(ProjectionMappers.select(
                        Path.of(Employee::getId),
                        Path.of(Employee::getName),
                        Path.of(Employee::getEmail),
                        Path.of(Employee::getSalary),
                        Path.of(Employee::getActive)))
                .where(Employee::getId).eq(1L)
                .list();

        // Then
        assertThat(results).hasSize(1);
        Tuple5<Long, String, String, Double, Boolean> tuple = results.getFirst();
        assertThat(tuple.get0()).isEqualTo(1L);
        assertThat(tuple.get1()).isEqualTo("Alice Johnson");
        assertThat(tuple.get4()).isTrue();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct five paths")
    void shouldSelectDistinctFivePaths(IntegrationTestContext context) {
        // When
        List<Tuple5<Long, Boolean, String, Double, String>> results = context.queryEmployees()
                .distinct().project(ProjectionMappers.select(
                        Path.of(Employee::getDepartmentId),
                        Path.of(Employee::getActive),
                        Path.of(Employee::getName),
                        Path.of(Employee::getSalary),
                        Path.of(Employee::getEmail)))
                .orderBy(Employee::getDepartmentId).asc()
                .list();

        // Then
        assertThat(results).hasSize(12);
    }

    // ========================================
    // 6. Tuple6 Selection (Six Paths)
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select six paths as Tuple6")
    void shouldSelectSixPaths(IntegrationTestContext context) {
        // When
        List<Tuple6<Long, String, String, Double, Boolean, Long>> results = context.queryEmployees()
                .project(ProjectionMappers.select(
                        Path.of(Employee::getId),
                        Path.of(Employee::getName),
                        Path.of(Employee::getEmail),
                        Path.of(Employee::getSalary),
                        Path.of(Employee::getActive),
                        Path.of(Employee::getDepartmentId)))
                .where(Employee::getId).eq(1L)
                .list();

        // Then
        assertThat(results).hasSize(1);
        Tuple6<Long, String, String, Double, Boolean, Long> tuple = results.getFirst();
        assertThat(tuple.get0()).isEqualTo(1L);
        assertThat(tuple.get5()).isEqualTo(1L);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct six paths")
    void shouldSelectDistinctSixPaths(IntegrationTestContext context) {
        // When
        List<Tuple6<Long, Boolean, String, Double, String, Long>> results = context.queryEmployees()
                .distinct().project(ProjectionMappers.select(
                        Path.of(Employee::getDepartmentId),
                        Path.of(Employee::getActive),
                        Path.of(Employee::getName),
                        Path.of(Employee::getSalary),
                        Path.of(Employee::getEmail),
                        Path.of(Employee::getId)))
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(results).hasSize(12);
    }

    // ========================================
    // 7. TypedExpression Selection（聚合 max / min / count / sum / avg）
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
    @DisplayName("Should select with typed expression - min")
    void shouldSelectWithTypedExpressionMin(IntegrationTestContext context) {
        // When
        Double minSalary = context.queryEmployees()
                .select(Path.of(Employee::getSalary).min())
                .single();

        // Then
        assertThat(minSalary).isNotNull();
        assertThat(minSalary).isGreaterThan(0);
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

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select with typed expression - avg")
    void shouldSelectWithTypedExpressionAvg(IntegrationTestContext context) {
        // When
        Double avg = context.queryEmployees()
                .select(Path.of(Employee::getSalary).avg())
                .single();

        // Then
        assertThat(avg).isNotNull();
        assertThat(avg).isGreaterThan(0);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct with typed expression")
    void shouldSelectDistinctWithTypedExpression(IntegrationTestContext context) {
        // When
        Long distinctDeptCount = context.queryEmployees()
                .distinct().select(Path.of(Employee::getDepartmentId).count())
                .single();

        // Then
        assertThat(distinctDeptCount).isNotNull();
    }

    // ========================================
    // 8. Multiple TypedExpression Selection
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select with two typed expressions")
    void shouldSelectWithTwoTypedExpressions(IntegrationTestContext context) {
        // When
        List<Tuple2<Double, Double>> results = context.queryEmployees()
                .select(Path.of(Employee::getSalary).min(), Path.of(Employee::getSalary).max())
                .list();

        // Then
        assertThat(results).hasSize(1);
        Tuple2<Double, Double> tuple = results.getFirst();
        assertThat(tuple.get0()).isLessThanOrEqualTo(tuple.get1());
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select with three typed expressions")
    void shouldSelectWithThreeTypedExpressions(IntegrationTestContext context) {
        // When
        List<Tuple3<Double, Double, Double>> results = context.queryEmployees()
                .select(Path.of(Employee::getSalary).min(), Path.of(Employee::getSalary).max(),
                        Path.of(Employee::getSalary).avg())
                .list();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().get0()).isLessThanOrEqualTo(results.getFirst().get1());
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select with four typed expressions")
    void shouldSelectWithFourTypedExpressions(IntegrationTestContext context) {
        // When
        List<Tuple4<Double, Double, Double, Long>> results = context.queryEmployees()
                .project(ProjectionMappers.select(
                        Path.of(Employee::getSalary).min(),
                        Path.of(Employee::getSalary).max(),
                        Path.of(Employee::getSalary).avg(),
                        Path.of(Employee::getId).count()))
                .list();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().get3()).isEqualTo(12L);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select with five typed expressions")
    void shouldSelectWithFiveTypedExpressions(IntegrationTestContext context) {
        // When
        List<Tuple5<Double, Double, Double, Long, Double>> results = context.queryEmployees()
                .project(ProjectionMappers.select(
                        Path.of(Employee::getSalary).min(),
                        Path.of(Employee::getSalary).max(),
                        Path.of(Employee::getSalary).avg(),
                        Path.of(Employee::getId).count(),
                        Path.of(Employee::getSalary).sum()))
                .list();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().get3()).isEqualTo(12L);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select with six typed expressions")
    void shouldSelectWithSixTypedExpressions(IntegrationTestContext context) {
        // When
        List<Tuple6<Double, Double, Double, Long, Double, Long>> results = context.queryEmployees()
                .project(ProjectionMappers.select(
                        Path.of(Employee::getSalary).min(),
                        Path.of(Employee::getSalary).max(),
                        Path.of(Employee::getSalary).avg(),
                        Path.of(Employee::getId).count(),
                        Path.of(Employee::getSalary).sum(),
                        Path.of(Employee::getDepartmentId).count()))
                .list();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().get3()).isEqualTo(12L);
    }

    // ========================================
    // 9. distinct 变长表达式投影
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct with two typed expressions varargs")
    void shouldSelectDistinctWithTwoTypedExpressionsVarargs(IntegrationTestContext context) {
        // When
        List<Tuple2<Double, Double>> results = context.queryEmployees()
                .distinct().select(Path.of(Employee::getSalary).min(), Path.of(Employee::getSalary).max())
                .list();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().get0()).isLessThanOrEqualTo(results.getFirst().get1());
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct with three typed expressions varargs")
    void shouldSelectDistinctWithThreeTypedExpressionsVarargs(IntegrationTestContext context) {
        // When
        List<Tuple3<Double, Double, Double>> results = context.queryEmployees()
                .distinct().select(Path.of(Employee::getSalary).min(), Path.of(Employee::getSalary).max(),
                        Path.of(Employee::getSalary).avg())
                .list();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().get0()).isLessThanOrEqualTo(results.getFirst().get1());
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct with four typed expressions varargs")
    void shouldSelectDistinctWithFourTypedExpressionsVarargs(IntegrationTestContext context) {
        // When
        List<Tuple4<Double, Double, Double, Long>> results = context.queryEmployees()
                .distinct().project(ProjectionMappers.select(
                        Path.of(Employee::getSalary).min(),
                        Path.of(Employee::getSalary).max(),
                        Path.of(Employee::getSalary).avg(),
                        Path.of(Employee::getId).count()))
                .list();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().get3()).isEqualTo(12L);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct with five typed expressions varargs")
    void shouldSelectDistinctWithFiveTypedExpressionsVarargs(IntegrationTestContext context) {
        // When
        List<Tuple5<Double, Double, Double, Long, Double>> results = context.queryEmployees()
                .distinct().project(ProjectionMappers.select(
                        Path.of(Employee::getSalary).min(),
                        Path.of(Employee::getSalary).max(),
                        Path.of(Employee::getSalary).avg(),
                        Path.of(Employee::getId).count(),
                        Path.of(Employee::getSalary).sum()))
                .list();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().get3()).isEqualTo(12L);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct with six typed expressions varargs")
    void shouldSelectDistinctWithSixTypedExpressionsVarargs(IntegrationTestContext context) {
        // When
        List<Tuple6<Double, Double, Double, Long, Double, Long>> results = context.queryEmployees()
                .distinct().project(ProjectionMappers.select(
                        Path.of(Employee::getSalary).min(),
                        Path.of(Employee::getSalary).max(),
                        Path.of(Employee::getSalary).avg(),
                        Path.of(Employee::getId).count(),
                        Path.of(Employee::getSalary).sum(),
                        Path.of(Employee::getDepartmentId).count()))
                .list();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().get3()).isEqualTo(12L);
    }

    // ========================================
    // 10. Selection with Conditions and Ordering
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
    // 11. Edge Cases
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
