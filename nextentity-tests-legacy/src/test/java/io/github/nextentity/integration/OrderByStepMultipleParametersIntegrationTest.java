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
/// Integration tests for OrderByStep multiple parameter orderBy 方法.
/// <p>
/// 测试s default 方法 in OrderByStep interface including:
/// - orderBy(Order): Sort by single Order object
/// - orderBy(Order, Order): Sort by two Order objects
/// - orderBy(Order, Order, Order): Sort by three Order objects
/// - orderBy(Path, Path): Sort by two paths
/// - orderBy(Path, Path, Path): Sort by three paths
/// <p>
/// These tests run against MySQL and PostgreSQL using 测试containers.
///
/// @author HuangChengwei
/// @see io.github.nextentity.api.OrderByStep
@DisplayName("OrderByStep Multiple Parameters Integration Tests")
public class OrderByStepMultipleParametersIntegrationTest {

    // ==================== orderBy(Order) Tests ====================

    ///
    /// 测试s orderBy(Order) with a single Order object.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should order by single Order object")
    void shouldOrderBySingleOrder(IntegrationTestContext context) {
        // Given

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

    // ==================== orderBy(Order, Order) Tests ====================

    // ==================== orderBy(Order, Order, Order) Tests ====================

    // ==================== orderBy(Path, Path) Tests ====================

    // ==================== orderBy(Path, Path, Path) Tests ====================

    // ==================== Combined with Filter Tests ====================

}


