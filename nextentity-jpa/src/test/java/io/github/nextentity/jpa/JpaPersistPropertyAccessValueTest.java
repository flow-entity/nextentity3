package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.jpa.entity.NormalizedPropertyAccess;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `@Access(PROPERTY)` 实体的取值必须跟着 **access type** 走：映射声明在 getter 上，
/// 取值也必须走 getter。否则同一个对象在两条写路径下落库两个不同的值：
///
/// - 路径 A（托管实体 + flush）：Hibernate 脏检查 / UPDATE 按 access type 读 getter；
/// - 路径 B（游离实体 + `update(entity)`）：`JpaPersistExecutor` 拼 `SET` 时走
///   `EntityAttribute#get`，只读字段就会拿到**字段原值**。
///
/// oracle 是差分而不是手算常量：同一个实体、同一份改动（`"  padded  "`），
/// 断言两条路径写进库的**原始列值**相等。读库走原生 SQL 而不是 `find(...)`：
/// 后者会经 getter 归一化，把差异盖住（`"  padded  "` 读回来也是 `"padded"`）。
class JpaPersistPropertyAccessValueTest extends JpaTestSupport {

    private static final String RAW_LABEL = "select label from normalized_property_access where id = ?";

    @BeforeEach
    void seedRows() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from NormalizedPropertyAccess").executeUpdate();
            entityManager.persist(new NormalizedPropertyAccess(1L, "orig", null));
            entityManager.persist(new NormalizedPropertyAccess(2L, "orig", null));
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("脏检查与 update(entity) 必须把同一个归一化后的值写进库")
    void updateWritesTheSameValueAsDirtyChecking() {
        String dirtyChecked = inTx(entityManager -> {
            NormalizedPropertyAccess managed = entityManager.find(NormalizedPropertyAccess.class, 1L);
            managed.setLabel("  padded  ");
            entityManager.flush();
            return rawLabel(entityManager, 1L);
        });

        inTx(entityManager -> {
            new JpaPersistExecutor<>(entityManager, meta(), NormalizedPropertyAccess.class)
                    .update(new NormalizedPropertyAccess(2L, "  padded  ", 0L));
            return null;
        });
        String viaUpdate = inTx(entityManager -> rawLabel(entityManager, 2L));

        assertThat(dirtyChecked).as("路径 A 是参照：Hibernate 按 getter 取到 trim 后的值").isEqualTo("padded");
        assertThat(viaUpdate).as("路径 B 必须与路径 A 落库同一个值（getter 是映射声明成员，取值也得走它）").isEqualTo(dirtyChecked);
    }

    @Test
    @DisplayName("update(entity) 的版本回写落在 getter 能读到的位置")
    void versionWriteBackIsVisibleThroughTheGetter() {
        NormalizedPropertyAccess detached = new NormalizedPropertyAccess(2L, "renamed", 0L);

        inTx(entityManager -> {
            new JpaPersistExecutor<>(entityManager, meta(), NormalizedPropertyAccess.class).update(detached);
            return null;
        });

        assertThat(detached.getVersion())
                .as("回写的版本号必须能被同一 access type 读出来，否则下一次 update 会撞乐观锁")
                .isEqualTo(1L);
        String label = inTx(entityManager -> rawLabel(entityManager, 2L));
        assertThat(label).isEqualTo("renamed");
    }

    private static String rawLabel(EntityManager entityManager, long id) {
        List<?> rows =
                entityManager.createNativeQuery(RAW_LABEL).setParameter(1, id).getResultList();
        return (String) rows.getFirst();
    }
}
