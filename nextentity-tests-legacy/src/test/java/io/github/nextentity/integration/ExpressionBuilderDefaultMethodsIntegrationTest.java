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
/// Integration tests for the `IfNotNull` string operator.
///
/// 2.2.3 的 `ExpressionBuilder.StringOperator` / `NumberOperator` 在 3.x 已整体删除：
/// `api/StringOperator.java` 只留 `Comparison + Nullness + Membership + StringOps`，
/// 没有 `likeIfNotEmpty` / `substring` / `length`，`NumberPath` 也没有 `add/subtract/multiply/divide/mod`。
/// 可对位的只有 `StringOps.likeIfNotNull`（调用方自带通配符，`null` 短路），
/// 其余用例已删除（见 NOT-PORTED-port-b.md）。
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
}
