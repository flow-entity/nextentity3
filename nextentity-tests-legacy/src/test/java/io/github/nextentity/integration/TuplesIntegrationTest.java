package io.github.nextentity.integration;

import io.github.nextentity.api.Path;

import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.entity.Employee;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

///
/// Integration tests for Tuples class with database queries.
///
/// 3.x 只有 `Tuple2`（`api/model` 下没有 `Tuple3+`、也没有无编号 `Tuple`），
/// 因此原 `shouldCreateTuple3FromQueryProjection` 已删除（见 NOT-PORTED-port-b.md）。
///
/// @author HuangChengwei
@DisplayName("Tuples Integration Tests")
public class TuplesIntegrationTest {

    @Nested
    @DisplayName("Tuple Query Integration Tests")
    class TupleQueryIntegrationTest {

        @ParameterizedTest
        @ArgumentsSource(IntegrationTestProvider.class)
        @DisplayName("Should create Tuple2 from query projection")
        void shouldCreateTuple2FromQueryProjection(IntegrationTestContext context) {
            // When
            List<Tuple2<String, Double>> results = context.queryEmployees()
                    .select(Path.of(Employee::getName), Path.of(Employee::getSalary))
                    .where(Employee::getId).eq(1L)
                    .list();

            // Then
            assertThat(results).isNotEmpty();
            Tuple2<String, Double> tuple = results.get(0);
            assertThat(tuple.get0()).isNotNull();
            assertThat(tuple.get1()).isNotNull();
        }

        @ParameterizedTest
        @ArgumentsSource(IntegrationTestProvider.class)
        @DisplayName("Should handle multiple tuple results")
        void shouldHandleMultipleTupleResults(IntegrationTestContext context) {
            // When
            List<Tuple2<String, Double>> results = context.queryEmployees()
                    .select(Path.of(Employee::getName), Path.of(Employee::getSalary))
                    .orderBy(Employee::getId).asc()
                    .list(5);

            // Then
            assertThat(results).isNotEmpty();
            assertThat(results.size()).isLessThanOrEqualTo(5);
            for (Tuple2<String, Double> tuple : results) {
                assertThat(tuple.get0()).isNotNull();
            }
        }
    }
}
