package io.github.nextentity.integration;

import io.github.nextentity.api.model.Slice;
import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.entity.Employee;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OrderOperator Uncovered Methods Integration Tests")
public class OrderOperatorUncoveredMethodsIntegrationTest {

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should slice with offset and limit without explicit sort order")
    void shouldSliceWithOffsetAndLimitWithoutExplicitSortOrder(IntegrationTestContext context) {
        Slice<Employee> slice = context.queryEmployees()
                .orderBy(Employee::getId).asc()
                .slice(0, 5);

        assertThat(slice.data()).hasSize(5);
        assertThat(slice.total()).isPositive();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should slice with where clause")
    void shouldSliceWithWhereClause(IntegrationTestContext context) {
        Slice<Employee> slice = context.queryEmployees()
                .where(Employee::getSalary).gt(50000.0)
                .orderBy(Employee::getId).asc()
                .slice(0, 5);

        assertThat(slice.data()).isNotEmpty();
        assertThat(slice.data()).allMatch(e -> e.getSalary() > 50000.0);
    }

}


