package io.github.nextentity.integration;

import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.entity.LockableEntity;
import io.github.nextentity.api.LockMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

///
/// Integration tests for Collector 方法 with LockMode parameters.
/// <p>
/// 测试s default 方法 in Collector interface that accept LockMode:
/// - first()/single() with lock
/// - window()/limit()/list() with lock
/// - slice(int, int)
/// <p>
/// These tests run against MySQL and PostgreSQL using 测试containers.
///
/// @author HuangChengwei
/// @see io.github.nextentity.api.Collector
@DisplayName("Collector LockMode Methods Integration Tests")
public class CollectorLockModeMethodsIntegrationTest {

    // ==================== first(LockMode) Tests ====================

    ///
    /// 测试s first(LockMode) returns value.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should first with LockMode return value")
    void shouldFirstWithLockModeReturnValue(IntegrationTestContext context) {
        // When
        LockableEntity result = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_READ).first()
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
    }

    ///
    /// 测试s first(LockMode) with pessimistic write lock.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should first with pessimistic write lock")
    void shouldFirstWithPessimisticWriteLock(IntegrationTestContext context) {
        // When
        LockableEntity result = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_WRITE).first()
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
    }

    // ==================== getFirst(LockMode) Tests ====================

    ///
    /// 测试s getFirst(LockMode) returns result.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should getFirst with LockMode return result")
    void shouldGetFirstWithLockModeReturnResult(IntegrationTestContext context) {
        // When
        LockableEntity entity = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_READ).first()
        );

        // Then
        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isEqualTo(1L);
    }

    ///
    /// 测试s getFirst(LockMode) with pessimistic write lock.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should getFirst with pessimistic write lock")
    void shouldGetFirstWithPessimisticWriteLock(IntegrationTestContext context) {
        // When
        LockableEntity entity = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_WRITE).first()
        );

        // Then
        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isEqualTo(1L);
    }

    // ==================== requireSingle(LockMode) Tests ====================

    ///
    /// 测试s requireSingle(LockMode) returns result when single exists.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should requireSingle with LockMode return result")
    void shouldRequireSingleWithLockModeReturnResult(IntegrationTestContext context) {
        // When
        LockableEntity entity = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .lock(LockMode.PESSIMISTIC_READ).single()
        );

        // Then
        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isEqualTo(1L);
    }

    ///
    /// 测试s requireSingle(LockMode) with pessimistic write lock.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should requireSingle with pessimistic write lock")
    void shouldRequireSingleWithPessimisticWriteLock(IntegrationTestContext context) {
        // When
        LockableEntity entity = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .lock(LockMode.PESSIMISTIC_WRITE).single()
        );

        // Then
        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isEqualTo(1L);
    }

    // ==================== single(LockMode) Tests ====================

    ///
    /// 测试s single(LockMode) returns value.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should single with LockMode return value")
    void shouldSingleWithLockModeReturnValue(IntegrationTestContext context) {
        // When
        LockableEntity result = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .lock(LockMode.PESSIMISTIC_READ).single()
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
    }

    ///
    /// 测试s single(LockMode) returns empty when no result.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should single with LockMode return empty when no result")
    void shouldSingleWithLockModeReturnEmptyWhenNoResult(IntegrationTestContext context) {
        // When
        LockableEntity result = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(999L)
                        .lock(LockMode.PESSIMISTIC_READ).single()
        );

        // Then
        assertThat(result).isNull();
    }

    ///
    /// 测试s single(LockMode) with pessimistic write lock.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should single with pessimistic write lock")
    void shouldSingleWithPessimisticWriteLock(IntegrationTestContext context) {
        // When
        LockableEntity result = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .lock(LockMode.PESSIMISTIC_WRITE).single()
        );

        // Then
        assertThat(result).isNotNull();
    }

    // ==================== getSingle(LockMode) Tests ====================

    ///
    /// 测试s getSingle(LockMode) returns result.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should getSingle with LockMode return result")
    void shouldGetSingleWithLockModeReturnResult(IntegrationTestContext context) {
        // When
        LockableEntity entity = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .lock(LockMode.PESSIMISTIC_READ).single()
        );

        // Then
        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isEqualTo(1L);
    }

    ///
    /// 测试s getSingle(LockMode) returns null when no result.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should getSingle with LockMode return null when no result")
    void shouldGetSingleWithLockModeReturnNullWhenNoResult(IntegrationTestContext context) {
        // When
        LockableEntity entity = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(999L)
                        .lock(LockMode.PESSIMISTIC_READ).single()
        );

        // Then
        assertThat(entity).isNull();
    }

    ///
    /// 测试s getSingle(LockMode) with pessimistic write lock.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should getSingle with pessimistic write lock")
    void shouldGetSingleWithPessimisticWriteLock(IntegrationTestContext context) {
        // When
        LockableEntity entity = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .lock(LockMode.PESSIMISTIC_WRITE).single()
        );

        // Then
        assertThat(entity).isNotNull();
    }
    ///
    /// 测试s getList with lock returns all results (no offset).
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should getList with lock return all results")
    void shouldOffsetZeroWithLockModeReturnAllResults(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_READ).list()
        );

        // Then
        assertThat(entities).hasSize(5);
    }

    // ==================== limit(int, LockMode) Tests ====================

    ///
    /// 测试s limit(int, LockMode) returns limited results.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should limit with LockMode return limited results")
    void shouldLimitWithLockModeReturnLimitedResults(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_READ).list(3)
        );

        // Then
        assertThat(entities).hasSize(3);
    }

    ///
    /// 测试s limit(int, LockMode) with pessimistic write lock.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should limit with pessimistic write lock")
    void shouldLimitWithPessimisticWriteLock(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_WRITE).list(2)
        );

        // Then
        assertThat(entities).hasSize(2);
    }

    // ==================== getList(LockMode) Tests ====================

    ///
    /// 测试s getList(LockMode) returns all results.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should getList with LockMode return all results")
    void shouldGetListWithLockModeReturnAllResults(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_READ).list()
        );

        // Then
        assertThat(entities).hasSize(5);
    }

    ///
    /// 测试s getList(LockMode) with pessimistic write lock.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should getList with pessimistic write lock")
    void shouldGetListWithPessimisticWriteLock(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_WRITE).list()
        );

        // Then
        assertThat(entities).hasSize(5);
    }
    // ==================== single with lock Tests ====================

    ///
    /// 测试s single with lock.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should single with lock return value")
    void shouldSingleWithOffsetAndLockModeReturnValue(IntegrationTestContext context) {
        // When
        LockableEntity result = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(3L)
                        .lock(LockMode.PESSIMISTIC_READ).single()
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(3L);
    }

    // ==================== Combined Tests ====================

    ///
    /// 测试s first(LockMode) with where clause.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should first with LockMode and where clause")
    void shouldFirstWithLockModeAndWhereClause(IntegrationTestContext context) {
        // When
        LockableEntity result = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).gt(2L)
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_READ).first()
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(3L);
    }

    ///
    /// 测试s getSingle(LockMode) throws when multiple results.
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should getSingle with LockMode throw when multiple results")
    void shouldGetSingleWithLockModeThrowWhenMultipleResults(IntegrationTestContext context) {
        // When & Then
        assertThatThrownBy(() ->
                context.doInTransaction(() ->
                        context.queryLockableEntities()
                                .where(LockableEntity::getId).lt(3L)
                                .lock(LockMode.PESSIMISTIC_READ).single()
                )
        ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("found more than one");
    }
}




