package io.github.nextentity.integration;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.Predicate;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.entity.Department;
import io.github.nextentity.integration.entity.Employee;
import io.github.nextentity.integration.entity.EmployeeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

///
/// Integration tests for QueryBuilder.
///
/// 3.x 的对位改动只有调用形态：`.list(offset, limit)` → `.slice(offset, limit).data()`；
/// `Predicate.and(PathRef)` → `Predicate.and(Path.of(...).op(...))`。断言原样保留。
/// `avg` / `min` / `select(Class)` / `Tuple3+` / 多列 `selectDistinct` / 算术表达式 / `Predicate.not()`
/// 对应的用例已删除（见 `tmp/legacy/NOT-PORTED-port-b.md`）。
class EntityQueryIntegrationTest {

    // Test data constants - Employees
    private static final int TOTAL_TEST_EMPLOYEES = 12;
    private static final double MAX_SALARY = 85000.0;
    private static final String ALICE_NAME = "Alice Johnson";
    private static final String BOB_NAME = "Bob Smith";

    // Test data constants - Departments
    private static final int TOTAL_DEPARTMENTS = 5;

    // Pagination constants
    private static final int PAGE_SIZE = 5;
    private static final int OFFSET = 3;

    // Salary range for between tests
    private static final double MIN_SALARY_RANGE = 60000.0;
    private static final double MAX_SALARY_RANGE = 75000.0;

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void getList_ShouldReturnAllEntities(IntegrationTestContext context) {
        // when
        List<Employee> employees = context.queryEmployees().list();

        // then
        assertThat(employees).isNotEmpty();
        assertThat(employees).hasSize(TOTAL_TEST_EMPLOYEES);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void where_WithEqualityCondition_ShouldFilterResults(IntegrationTestContext context) {
        // when
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getName).eq(ALICE_NAME)
                .list();

        // then
        assertThat(employees).hasSize(1);
        assertThat(employees.get(0).getName()).isEqualTo(ALICE_NAME);
    }

    ///
    /// 测试s multiple WHERE conditions with AND logic using Predicate.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void where_WithMultipleConditions_ShouldFilterResults(IntegrationTestContext context) {
        // given
        Predicate<Employee> isActive = Path.of(Employee::getActive).eq(true);

        // when
        List<Employee> employees = context.queryEmployees()
                .where(isActive.and(Path.of(Employee::getStatus).eq(EmployeeStatus.ACTIVE)))
                .list();

        // then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getActive() && e.getStatus() == EmployeeStatus.ACTIVE);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void orderBy_WithAscendingOrder_ShouldSortResults(IntegrationTestContext context) {
        // when
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getSalary).asc()
                .list();

        // then
        assertThat(employees).isNotEmpty();
        for (int i = 1; i < employees.size(); i++) {
            assertThat(employees.get(i).getSalary())
                    .isGreaterThanOrEqualTo(employees.get(i - 1).getSalary());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void orderBy_WithDescendingOrder_ShouldSortResults(IntegrationTestContext context) {
        // when
        List<Employee> employees = context.queryEmployees()
                .orderBy(Employee::getSalary).desc()
                .list();

        // then
        assertThat(employees).isNotEmpty();
        for (int i = 1; i < employees.size(); i++) {
            assertThat(employees.get(i).getSalary())
                    .isLessThanOrEqualTo(employees.get(i - 1).getSalary());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void getFirst_ShouldReturnFirstResult(IntegrationTestContext context) {
        // when
        Employee employee = context.queryEmployees()
                .orderBy(Employee::getSalary).desc()
                .first();

        // then
        assertThat(employee).isNotNull();
        assertThat(employee.getSalary()).isEqualTo(MAX_SALARY);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void limit_ShouldRestrictResultCount(IntegrationTestContext context) {
        // when
        List<Employee> employees = context.queryEmployees()
                .list(PAGE_SIZE);

        // then
        assertThat(employees).hasSize(PAGE_SIZE);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void offset_ShouldSkipResults(IntegrationTestContext context) {
        // given
        List<Employee> allEmployees = context.queryEmployees()
                .orderBy(Employee::getId).asc()
                .list();

        // when
        List<Employee> pagedEmployees = context.queryEmployees()
                .orderBy(Employee::getId).asc()
                .slice(OFFSET, PAGE_SIZE)
                .data();

        // then
        assertThat(pagedEmployees).hasSize(PAGE_SIZE);
        assertThat(pagedEmployees.get(0).getId()).isEqualTo(allEmployees.get(OFFSET).getId());
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void select_WithSingleField_ShouldReturnProjectedResults(IntegrationTestContext context) {
        // when
        List<String> names = context.queryEmployees()
                .select(Employee::getName)
                .list();

        // then
        assertThat(names).isNotEmpty();
        assertThat(names).hasSize(TOTAL_TEST_EMPLOYEES);
        assertThat(names).contains(ALICE_NAME, BOB_NAME);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void select_WithMultipleFields_ShouldReturnTupleResults(IntegrationTestContext context) {
        // when
        List<Tuple2<String, Double>> results = context.queryEmployees()
                .select(Path.of(Employee::getName), Path.of(Employee::getSalary))
                .list();

        // then
        assertThat(results).isNotEmpty();
        assertThat(results).hasSize(TOTAL_TEST_EMPLOYEES);

        Tuple2<String, Double> first = results.get(0);
        assertThat(first.get0()).isNotNull();
        assertThat(first.get1()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void selectDistinct_ShouldReturnUniqueResults(IntegrationTestContext context) {
        // when
        List<Long> deptIds = context.queryEmployees()
                .selectDistinct(Employee::getDepartmentId)
                .list();

        // then
        assertThat(deptIds).isNotEmpty();
        assertThat(deptIds).hasSize(TOTAL_DEPARTMENTS);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void where_WithInClause_ShouldFilterResults(IntegrationTestContext context) {
        // when
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getDepartmentId).in(1L, 2L)
                .list();

        // then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getDepartmentId() == 1L || e.getDepartmentId() == 2L);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void where_WithBetweenClause_ShouldFilterResults(IntegrationTestContext context) {
        // when
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getSalary).between(MIN_SALARY_RANGE, MAX_SALARY_RANGE)
                .list();

        // then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getSalary() >= MIN_SALARY_RANGE && e.getSalary() <= MAX_SALARY_RANGE);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void where_WithLikeClause_ShouldFilterResults(IntegrationTestContext context) {
        // when
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getEmail).likeIfNotNull("%@example.com")
                .list();

        // then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getEmail().endsWith("@example.com"));
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void where_WithIsNotNull_ShouldFilterResults(IntegrationTestContext context) {
        // when
        List<Employee> employees = context.queryEmployees()
                .where(Employee::getDepartmentId).isNotNull()
                .list();

        // then
        assertThat(employees).isNotEmpty();
        assertThat(employees).allMatch(e -> e.getDepartmentId() != null);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void select_WithCount_ShouldReturnCount(IntegrationTestContext context) {
        // when
        Long count = context.queryEmployees()
                .select(Path.of(Employee::getId).count())
                .first();

        // then
        assertThat(count).isEqualTo(TOTAL_TEST_EMPLOYEES);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void select_WithSum_ShouldReturnSum(IntegrationTestContext context) {
        // when
        Double sum = context.queryEmployees()
                .select(Path.of(Employee::getSalary).sum())
                .first();

        // then
        assertThat(sum).isPositive();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void select_WithMax_ShouldReturnMax(IntegrationTestContext context) {
        // when
        Double max = context.queryEmployees()
                .select(Path.of(Employee::getSalary).max())
                .first();

        // then
        assertThat(max).isEqualTo(MAX_SALARY);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void where_WithOrCondition_ShouldFilterResults(IntegrationTestContext context) {
        // given
        Predicate<Employee> isAlice = Path.of(Employee::getName).eq(ALICE_NAME);

        // when
        List<Employee> employees = context.queryEmployees()
                .where(isAlice.or(Employee::getName).eq(BOB_NAME))
                .list();

        // then
        assertThat(employees).hasSize(2);
        assertThat(employees).extracting(Employee::getName)
                .containsExactlyInAnyOrder(ALICE_NAME, BOB_NAME);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    void query_Departments_ShouldReturnAllDepartments(IntegrationTestContext context) {
        // when
        List<Department> departments = context.queryDepartments().list();

        // then
        assertThat(departments).hasSize(TOTAL_DEPARTMENTS);
    }

    // ==================== Predicate as Query Condition Tests ====================

    @Nested
    @DisplayName("Predicate as Query Condition Tests")
    class PredicateAsQueryConditionTests {

        ///
        /// 测试s using eq predicate as query condition.
        @ParameterizedTest
        @ArgumentsSource(IntegrationTestProvider.class)
        @DisplayName("Should use eq predicate as where condition")
        void shouldUseEqPredicateAsWhereCondition(IntegrationTestContext context) {
            // Given
            Predicate<Employee> predicate = Path.of(Employee::getName).eq(ALICE_NAME);

            // When
            List<Employee> employees = context.queryEmployees()
                    .where(predicate)
                    .list();

            // Then
            assertThat(employees).hasSize(1);
            assertThat(employees.get(0).getName()).isEqualTo(ALICE_NAME);
        }

        ///
        /// 测试s using gt predicate as query condition.
        @ParameterizedTest
        @ArgumentsSource(IntegrationTestProvider.class)
        @DisplayName("Should use gt predicate as where condition")
        void shouldUseGtPredicateAsWhereCondition(IntegrationTestContext context) {
            // Given
            Predicate<Employee> predicate = Path.of(Employee::getSalary).gt(70000.0);

            // When
            List<Employee> employees = context.queryEmployees()
                    .where(predicate)
                    .orderBy(Employee::getSalary).asc()
                    .list();

            // Then
            assertThat(employees).isNotEmpty();
            assertThat(employees).allMatch(e -> e.getSalary() > 70000.0);
        }

        ///
        /// 测试s using between predicate as query condition.
        @ParameterizedTest
        @ArgumentsSource(IntegrationTestProvider.class)
        @DisplayName("Should use between predicate as where condition")
        void shouldUseBetweenPredicateAsWhereCondition(IntegrationTestContext context) {
            // Given
            Predicate<Employee> predicate = Path.of(Employee::getSalary).between(MIN_SALARY_RANGE, MAX_SALARY_RANGE);

            // When
            List<Employee> employees = context.queryEmployees()
                    .where(predicate)
                    .list();

            // Then
            assertThat(employees).isNotEmpty();
            assertThat(employees).allMatch(e -> e.getSalary() >= MIN_SALARY_RANGE && e.getSalary() <= MAX_SALARY_RANGE);
        }

        ///
        /// 测试s combining multiple predicates with AND.
        @ParameterizedTest
        @ArgumentsSource(IntegrationTestProvider.class)
        @DisplayName("Should combine predicates with AND")
        void shouldCombinePredicatesWithAnd(IntegrationTestContext context) {
            // Given
            Predicate<Employee> activePredicate = Path.of(Employee::getActive).eq(true);
            Predicate<Employee> salaryPredicate = Path.of(Employee::getSalary).gt(50000.0);
            Predicate<Employee> combined = activePredicate.and(salaryPredicate);

            // When
            List<Employee> employees = context.queryEmployees()
                    .where(combined)
                    .list();

            // Then
            assertThat(employees).isNotEmpty();
            assertThat(employees).allMatch(e -> e.getActive() && e.getSalary() > 50000.0);
        }

        ///
        /// 测试s combining multiple predicates with OR.
        @ParameterizedTest
        @ArgumentsSource(IntegrationTestProvider.class)
        @DisplayName("Should combine predicates with OR")
        void shouldCombinePredicatesWithOr(IntegrationTestContext context) {
            // Given
            Predicate<Employee> isAlice = Path.of(Employee::getName).eq(ALICE_NAME);
            Predicate<Employee> isBob = Path.of(Employee::getName).eq(BOB_NAME);
            Predicate<Employee> combined = isAlice.or(isBob);

            // When
            List<Employee> employees = context.queryEmployees()
                    .where(combined)
                    .list();

            // Then
            assertThat(employees).hasSize(2);
            assertThat(employees).extracting(Employee::getName)
                    .containsExactlyInAnyOrder(ALICE_NAME, BOB_NAME);
        }

        ///
        /// 测试s complex predicate expression.
        @ParameterizedTest
        @ArgumentsSource(IntegrationTestProvider.class)
        @DisplayName("Should create complex predicate expression")
        void shouldCreateComplexPredicateExpression(IntegrationTestContext context) {
            // Given: (active AND salary > 60000) OR name = 'Alice Johnson'
            Predicate<Employee> activeHighSalary = Path.of(Employee::getActive).eq(true)
                    .and(Path.of(Employee::getSalary).gt(60000.0));
            Predicate<Employee> isAlice = Path.of(Employee::getName).eq(ALICE_NAME);
            Predicate<Employee> complex = activeHighSalary.or(isAlice);

            // When
            List<Employee> employees = context.queryEmployees()
                    .where(complex)
                    .orderBy(Employee::getId).asc()
                    .list();

            // Then
            assertThat(employees).isNotEmpty();
            assertThat(employees).allMatch(e ->
                    (e.getActive() && e.getSalary() > 60000.0) || e.getName().equals(ALICE_NAME));
        }
    }
}
