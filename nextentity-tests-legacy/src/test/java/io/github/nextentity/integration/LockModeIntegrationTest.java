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
import static org.assertj.core.api.Assertions.assertThatNoException;

///
/// Lock mode integration tests.
/// <p>
/// 测试s pessimistic and optimistic locking mechanisms using
/// the getList 方法 with LockMode parameter.
/// <p>
/// Note: Lock 操作s require an active transaction.
/// All lock tests are wrapped in doInTransaction.
/// <p>
/// Uses LockableEntity which has a @Version field to support
/// optimistic locking (OPTIMISTIC, OPTIMISTIC_FORCE_INCREMENT).
/// <p>
/// These tests run against MySQL and PostgreSQL using 测试containers.
///
/// @author HuangChengwei
@DisplayName("Lock Mode Integration Tests")
public class LockModeIntegrationTest {

    // ========================================
    // 1. PESSIMISTIC_READ Lock Tests
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should query with pessimistic read lock")
    void shouldQueryWithPessimisticRead(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .lock(LockMode.PESSIMISTIC_READ).list(10)
        );

        // Then
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getName()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should get single with pessimistic read lock")
    void shouldGetSingleWithPessimisticRead(IntegrationTestContext context) {
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

    // ========================================
    // 2. PESSIMISTIC_WRITE Lock Tests
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should query with pessimistic write lock")
    void shouldQueryWithPessimisticWrite(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .lock(LockMode.PESSIMISTIC_WRITE).list(10)
        );

        // Then
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getName()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should get single with pessimistic write lock")
    void shouldGetSingleWithPessimisticWrite(IntegrationTestContext context) {
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

    // ========================================
    // 3. OPTIMISTIC Lock Tests
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should query with optimistic lock")
    void shouldQueryWithOptimistic(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .lock(LockMode.OPTIMISTIC).list(10)
        );

        // Then
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getName()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should query with optimistic force increment lock")
    void shouldQueryWithOptimisticForceIncrement(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .lock(LockMode.OPTIMISTIC_FORCE_INCREMENT).list(10)
        );

        // Then
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getName()).isNotNull();
    }

    // ========================================
    // 4. 2.2.3 的 READ / WRITE 对位（OPTIMISTIC / OPTIMISTIC_FORCE_INCREMENT）
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should query with optimistic lock (2.2.3 READ)")
    void shouldQueryWithReadLock(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .lock(LockMode.OPTIMISTIC).list(10)
        );

        // Then
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getName()).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should query with optimistic force increment lock (2.2.3 WRITE)")
    void shouldQueryWithWriteLock(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .lock(LockMode.OPTIMISTIC_FORCE_INCREMENT).list(10)
        );

        // Then
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getName()).isNotNull();
    }

    // ========================================
    // 5. 2.2.3 的 NONE 对位（不加锁）
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should query without lock (2.2.3 NONE)")
    void shouldQueryWithNoLock(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .list(10)
        );

        // Then
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getName()).isNotNull();
    }

    // ========================================
    // 6. Lock with Conditions
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should lock with where condition")
    void shouldLockWithWhereCondition(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getName).likeIfNotNull("Lockable%")
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_READ).list(10)
        );

        // Then
        assertThat(entities).isNotEmpty();
        assertThat(entities).allMatch(e -> e.getName().startsWith("Lockable"));
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should lock with multiple conditions")
    void shouldLockWithMultipleConditions(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getName).likeIfNotNull("Lockable%")
                        .where(LockableEntity::getId).lt(4L)
                        .lock(LockMode.PESSIMISTIC_WRITE).list(10)
        );

        // Then
        assertThat(entities).isNotEmpty();
        for (LockableEntity entity : entities) {
            assertThat(entity.getName()).startsWith("Lockable");
            assertThat(entity.getId()).isLessThan(4L);
        }
    }

    // ========================================
    // 7. Lock with Pagination
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should lock with pagination")
    void shouldLockWithPagination(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_READ).list(3)
        );

        // Then
        assertThat(entities).hasSize(3);
    }

    // ========================================
    // 8. Lock with First
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should get first with lock")
    void shouldGetFirstWithLock(IntegrationTestContext context) {
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

    // ========================================
    // 9. PESSIMISTIC_FORCE_INCREMENT Lock
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should query with pessimistic force increment lock")
    void shouldQueryWithPessimisticForceIncrement(IntegrationTestContext context) {
        // When & Then
        assertThatNoException().isThrownBy(() ->
                context.doInTransaction(() -> {
                    context.queryLockableEntities()
                            .where(LockableEntity::getId).eq(1L)
                            .lock(LockMode.PESSIMISTIC_FORCE_INCREMENT).list(10);
                    return null;
                })
        );
    }

    // ========================================
    // 10. Lock with In Condition
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should lock with in condition")
    void shouldLockWithInCondition(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).in(1L, 2L, 3L)
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_READ).list(10)
        );

        // Then
        assertThat(entities).hasSize(3);
    }

    // ========================================
    // 11. Null Lock Mode
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should query with null lock mode")
    void shouldQueryWithNullLockMode(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .where(LockableEntity::getId).eq(1L)
                        .list(10)
        );

        // Then
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getName()).isNotNull();
    }

    // ========================================
    // 12. Limit with Lock
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should limit with lock mode")
    void shouldLimitWithLockMode(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .orderBy(LockableEntity::getId).asc()
                        .lock(LockMode.PESSIMISTIC_READ).list(3)
        );

        // Then
        assertThat(entities).hasSize(3);
    }

    // ========================================
    // 13. GetList with Lock
    // ========================================

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should get list with lock mode")
    void shouldGetListWithLockMode(IntegrationTestContext context) {
        // When
        List<LockableEntity> entities = context.doInTransaction(() ->
                context.queryLockableEntities()
                        .lock(LockMode.PESSIMISTIC_READ).list()
        );

        // Then
        assertThat(entities).hasSize(5);
    }
}






