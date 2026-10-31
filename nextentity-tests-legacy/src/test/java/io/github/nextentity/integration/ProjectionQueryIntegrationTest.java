package io.github.nextentity.integration;

import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.dto.*;
import io.github.nextentity.integration.entity.Employee;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

///
/// Projection query integration tests.
/// <p>
/// Tests projection to different target types:
/// - JavaBean (class with default constructor and setters)
/// - Record (immutable data carrier)
/// <p>
/// 3.x 的 DTO 投影只有 `select(Class)` 一种入口，目标形态两类：record（属性名取规范构造器参数名，
/// 列序即参数序）与普通类（属性名由公开 `setXxx` 定位，需无参构造器，列序按属性名排序）；接口/抽象类
/// 不支持（要运行期代理，本轮不提供，抛 `IllegalArgumentException`）。证据 `api/SelectStep.java:68-91`
/// 与 `api/DistinctStep.java`。接口投影共 12 条、LAZY 接口投影 3 条，均删除，见
/// `porting/NOT-PORTED-round3-port-d.md`。
///
/// @author HuangChengwei
@DisplayName("Projection Query Integration Tests")
public class ProjectionQueryIntegrationTest {

    // ========================================
    // 1. JavaBean Projection Tests
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to JavaBean - EmployeeBasicInfo")
    void shouldProjectToJavaBeanBasicInfo(IntegrationTestContext context) {
        // When
        List<EmployeeBasicInfo> results = context.queryEmployees()
                .select(EmployeeBasicInfo.class)
                .where(Employee::getId).eq(1L)
                .list();

        // Then
        assertThat(results).hasSize(1);
        EmployeeBasicInfo info = results.get(0);
        assertThat(info.getId()).isEqualTo(1L);
        assertThat(info.getName()).isEqualTo("Alice Johnson");
        assertThat(info.getEmail()).isEqualTo("alice@example.com");
        assertThat(info.getSalary()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to JavaBean - EmployeeSummary")
    void shouldProjectToJavaBeanSummary(IntegrationTestContext context) {
        // When
        List<EmployeeSummary> results = context.queryEmployees()
                .select(EmployeeSummary.class)
                .where(Employee::getActive).eq(true)
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(results).isNotEmpty();
        for (EmployeeSummary summary : results) {
            assertThat(summary.getName()).isNotNull();
            assertThat(summary.getSalary()).isNotNull();
            assertThat(summary.getActive()).isTrue();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to JavaBean with multiple results")
    void shouldProjectToJavaBeanMultipleResults(IntegrationTestContext context) {
        // When
        List<EmployeeBasicInfo> results = context.queryEmployees()
                .select(EmployeeBasicInfo.class)
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(results).hasSize(12);
        assertThat(results.get(0).getName()).isEqualTo("Alice Johnson");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to JavaBean with where condition")
    void shouldProjectToJavaBeanWithWhere(IntegrationTestContext context) {
        // When
        List<EmployeeBasicInfo> results = context.queryEmployees()
                .select(EmployeeBasicInfo.class)
                .where(Employee::getSalary).gt(70000.0)
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(info -> info.getSalary() > 70000.0);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to JavaBean with pagination")
    void shouldProjectToJavaBeanWithPagination(IntegrationTestContext context) {
        // When
        List<EmployeeBasicInfo> results = context.queryEmployees()
                .select(EmployeeBasicInfo.class)
                .orderBy(Employee::getId).asc()
                .list(5);

        // Then
        assertThat(results).hasSize(5);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should return empty list when no match for JavaBean projection")
    void shouldReturnEmptyForJavaBeanNoMatch(IntegrationTestContext context) {
        // When
        List<EmployeeBasicInfo> results = context.queryEmployees()
                .select(EmployeeBasicInfo.class)
                .where(Employee::getId).eq(99999L)
                .list();

        // Then
        assertThat(results).isEmpty();
    }

    // ========================================
    // 2. Record Projection Tests
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to record - EmployeeRecord")
    void shouldProjectToRecordEmployeeRecord(IntegrationTestContext context) {
        // When
        List<EmployeeRecord> results = context.queryEmployees()
                .select(EmployeeRecord.class)
                .where(Employee::getId).eq(1L)
                .list();

        // Then
        assertThat(results).hasSize(1);
        EmployeeRecord record = results.get(0);
        assertThat(record.id()).isEqualTo(1L);
        assertThat(record.name()).isEqualTo("Alice Johnson");
        assertThat(record.email()).isEqualTo("alice@example.com");
        assertThat(record.salary()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to record - EmployeeNameSalary")
    void shouldProjectToRecordNameSalary(IntegrationTestContext context) {
        // When
        List<EmployeeNameSalary> results = context.queryEmployees()
                .select(EmployeeNameSalary.class)
                .where(Employee::getActive).eq(true)
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(results).isNotEmpty();
        for (EmployeeNameSalary item : results) {
            assertThat(item.name()).isNotNull();
            assertThat(item.salary()).isNotNull();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to record - DepartmentRecord")
    void shouldProjectToRecordDepartmentRecord(IntegrationTestContext context) {
        // When
        List<DepartmentRecord> results = context.queryDepartments()
                .select(DepartmentRecord.class)
                .orderBy(io.github.nextentity.integration.entity.Department::getId).asc()
                .list();

        // Then
        assertThat(results).hasSize(5);
        DepartmentRecord first = results.get(0);
        assertThat(first.id()).isEqualTo(1L);
        assertThat(first.name()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to record - EmployeeDetailRecord")
    void shouldProjectToRecordDetail(IntegrationTestContext context) {
        // When
        List<EmployeeDetailRecord> results = context.queryEmployees()
                .select(EmployeeDetailRecord.class)
                .where(Employee::getId).eq(1L)
                .list();

        // Then
        assertThat(results).hasSize(1);
        EmployeeDetailRecord detail = results.get(0);
        assertThat(detail.id()).isEqualTo(1L);
        assertThat(detail.name()).isEqualTo("Alice Johnson");
        assertThat(detail.salary()).isNotNull();
        assertThat(detail.active()).isTrue();
        assertThat(detail.departmentId()).isEqualTo(1L);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to record with multiple results")
    void shouldProjectToRecordMultipleResults(IntegrationTestContext context) {
        // When
        List<EmployeeRecord> results = context.queryEmployees()
                .select(EmployeeRecord.class)
                .orderBy(Employee::getId).asc()
                .list();

        // Then
        assertThat(results).hasSize(12);
        assertThat(results.get(0).name()).isEqualTo("Alice Johnson");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to record with where condition")
    void shouldProjectToRecordWithWhere(IntegrationTestContext context) {
        // When
        List<EmployeeRecord> results = context.queryEmployees()
                .select(EmployeeRecord.class)
                .where(Employee::getSalary).gt(70000.0)
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(r -> r.salary() > 70000.0);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to record with pagination")
    void shouldProjectToRecordWithPagination(IntegrationTestContext context) {
        // When
        List<EmployeeRecord> results = context.queryEmployees()
                .select(EmployeeRecord.class)
                .orderBy(Employee::getId).asc()
                .list(5);

        // Then
        assertThat(results).hasSize(5);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should get single result for record projection")
    void shouldGetSingleForRecord(IntegrationTestContext context) {
        // When
        EmployeeRecord result = context.queryEmployees()
                .select(EmployeeRecord.class)
                .where(Employee::getId).eq(1L)
                .single();

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should return empty list for record projection no match")
    void shouldReturnEmptyForRecordNoMatch(IntegrationTestContext context) {
        // When
        List<EmployeeRecord> results = context.queryEmployees()
                .select(EmployeeRecord.class)
                .where(Employee::getId).eq(99999L)
                .list();

        // Then
        assertThat(results).isEmpty();
    }

    // ========================================
    // 3. Distinct Projection Tests
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct JavaBean projection")
    void shouldSelectDistinctJavaBean(IntegrationTestContext context) {
        // When
        List<EmployeeSummary> results = context.queryEmployees()
                .distinct().select(EmployeeSummary.class)
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(results).hasSize(12); // All names are distinct
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should select distinct record projection")
    void shouldSelectDistinctRecord(IntegrationTestContext context) {
        // When
        List<EmployeeNameSalary> results = context.queryEmployees()
                .distinct().select(EmployeeNameSalary.class)
                .orderBy(Employee::getName).asc()
                .list();

        // Then
        assertThat(results).hasSize(12);
    }

    // ========================================
    // 4. Edge Cases and Error Handling
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should handle null values in JavaBean projection")
    void shouldHandleNullInJavaBean(IntegrationTestContext context) {
        // When
        List<EmployeeBasicInfo> results = context.queryEmployees()
                .select(EmployeeBasicInfo.class)
                .where(Employee::getId).eq(1L)
                .list();

        // Then
        assertThat(results).hasSize(1);
        // Name should not be null
        assertThat(results.get(0).getName()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should handle null values in record projection")
    void shouldHandleNullInRecord(IntegrationTestContext context) {
        // When
        List<EmployeeNullableRecord> results = context.queryEmployees()
                .select(EmployeeNullableRecord.class)
                .where(Employee::getId).eq(1L)
                .list();

        // Then
        assertThat(results).hasSize(1);
        // Name should not be null
        assertThat(results.get(0).name()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project to record with limit")
    void shouldProjectToRecordWithLimit(IntegrationTestContext context) {
        // When
        List<EmployeeRecord> results = context.queryEmployees()
                .select(EmployeeRecord.class)
                .orderBy(Employee::getId).asc()
                .list(3);

        // Then
        assertThat(results).hasSize(3);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project JavaBean with complex condition")
    void shouldProjectJavaBeanWithComplexCondition(IntegrationTestContext context) {
        // When
        List<EmployeeBasicInfo> results = context.queryEmployees()
                .select(EmployeeBasicInfo.class)
                .where(Employee::getSalary).gt(50000.0)
                .where(Employee::getActive).eq(true)
                .orderBy(Employee::getSalary).desc()
                .list();

        // Then
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(info -> info.getSalary() > 50000.0);
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project record with complex condition")
    void shouldProjectRecordWithComplexCondition(IntegrationTestContext context) {
        // When
        List<EmployeeRecord> results = context.queryEmployees()
                .select(EmployeeRecord.class)
                .where(Employee::getSalary).gt(50000.0)
                .where(Employee::getActive).eq(true)
                .orderBy(Employee::getSalary).desc()
                .list();

        // Then
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(r -> r.salary() > 50000.0);
    }
}
