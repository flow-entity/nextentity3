package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.Organization;
import io.github.nextentity.jpa.entity.Seal;
import io.github.nextentity.jpa.entity.UpdatableFlags;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 条件更新的写侧裁剪：`@Column(updatable=false)` 的标量列与 `@JoinColumn(updatable=false)` 的
/// 外键列出现在 `SET` 里必须 fail-fast，文案与 JDBC 后端**逐字一致**——同一调用打到两个后端要
/// 拿到同一句话，否则一致性只能断到"都抛了 UOE"这种弱结论。
///
/// 正向对照是必须的：只测拒绝的话，把「拒绝一切列」写成实现也能全绿。
class JpaConditionalWriteUpdatableTest extends JpaTestSupport {

    @BeforeEach
    void seedFlags() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from UpdatableFlags").executeUpdate();
            entityManager.createQuery("delete from Seal").executeUpdate();
            entityManager.persist(new Seal(1L, "gold"));
            entityManager.persist(new Seal(2L, "silver"));
            entityManager.persist(new UpdatableFlags(
                    1L,
                    "writable",
                    "locked",
                    entityManager.getReference(Organization.class, 1L),
                    entityManager.getReference(Organization.class, 1L),
                    entityManager.getReference(Seal.class, 1L)));
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("条件 update 写 @Column(updatable=false) 标量列：UOE，文案与 JDBC 逐字一致，且不落库")
    void scalarUpdateOfReadOnlyColumnIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> operations(entityManager)
                            .update()
                            .set(Path.of(UpdatableFlags::getLockedName), "changed")
                            .where(UpdatableFlags::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage(
                            "条件更新不支持 @Column(updatable=false) 列: " + UpdatableFlags.class.getName() + ".lockedName");
            return null;
        });

        assertThat(readFlags().getLockedName()).as("被拒绝的语句不得落库").isEqualTo("locked");
    }

    @Test
    @DisplayName("条件 update 写 @JoinColumn(updatable=false) 外键列：UOE，同一文案，且不落库")
    void associationUpdateOfReadOnlyJoinColumnIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> operations(entityManager)
                            .update()
                            .set(Path.of(UpdatableFlags::getLockedOwner), new Organization(2L, "Empty"))
                            .where(UpdatableFlags::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage(
                            "条件更新不支持 @Column(updatable=false) 列: " + UpdatableFlags.class.getName() + ".lockedOwner");
            return null;
        });

        inTx(entityManager -> {
            assertThat(entityManager
                            .find(UpdatableFlags.class, 1L)
                            .getLockedOwner()
                            .getId())
                    .as("被拒绝的语句不得落库")
                    .isEqualTo(1L);
            return null;
        });
    }

    @Test
    @DisplayName("条件 update 写 @OneToOne 的 @JoinColumn(updatable=false) 外键列：同样 UOE")
    void oneToOneUpdateOfReadOnlyJoinColumnIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> operations(entityManager)
                            .update()
                            .set(Path.of(UpdatableFlags::getLockedSeal), new Seal(2L, "other"))
                            .where(UpdatableFlags::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage(
                            "条件更新不支持 @Column(updatable=false) 列: " + UpdatableFlags.class.getName() + ".lockedSeal");
            return null;
        });

        assertThat(readFlags().getLockedSeal().getId()).as("被拒绝的语句不得落库").isEqualTo(1L);
    }

    @Test
    @DisplayName("元数据契约：不存在的属性没有列可写，按可更新处理（存在性校验留给 get 的那一步）")
    void unknownAttributeIsTreatedAsUpdatable() {
        assertThat(meta().entityType(UpdatableFlags.class).updatable("noSuchAttribute"))
                .isTrue();
    }

    @Test
    @DisplayName("正向：可更新的标量列与外键列照常写，只读列不受影响")
    void writableColumnsStillUpdate() {
        int affected = inTx(entityManager -> operations(entityManager)
                .update()
                .set(Path.of(UpdatableFlags::getName), "renamed")
                .set(Path.of(UpdatableFlags::getOwner), new Organization(2L, "Empty"))
                .where(UpdatableFlags::getId)
                .eq(1L)
                .execute());

        assertThat(affected).isEqualTo(1);
        inTx(entityManager -> {
            UpdatableFlags read = entityManager.find(UpdatableFlags.class, 1L);
            assertThat(read.getName()).isEqualTo("renamed");
            assertThat(read.getOwner().getId()).isEqualTo(2L);
            assertThat(read.getLockedName()).isEqualTo("locked");
            assertThat(read.getLockedOwner().getId()).isEqualTo(1L);
            return null;
        });
    }

    @Test
    @DisplayName("条件 delete 不受只读列影响：只读列只在 SET 侧被裁剪")
    void deleteIgnoresReadOnlyColumns() {
        int affected = inTx(entityManager -> operations(entityManager)
                .delete()
                .where(UpdatableFlags::getId)
                .eq(1L)
                .execute());

        assertThat(affected).isEqualTo(1);
        inTx(entityManager -> {
            assertThat(entityManager.find(UpdatableFlags.class, 1L)).isNull();
            return null;
        });
    }

    private UpdatableFlags readFlags() {
        return inTx(entityManager -> entityManager.find(UpdatableFlags.class, 1L));
    }

    private EntityOperations<UpdatableFlags> operations(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(UpdatableFlags.class);
    }
}
