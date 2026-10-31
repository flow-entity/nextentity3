package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.HomeAddress;
import io.github.nextentity.jpa.entity.OverrideReadOnlyFlags;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 只读列判定看**覆盖后的生效列**：`@AttributeOverride(column = @Column(updatable = false))` 把
/// 可写叶子覆盖成只读列后，条件更新必须 fail-fast、单实体 `update(entity)` 必须跳过该列。
///
/// 3.1.0 与 Unreleased 的修法都只读成员自己的注解（`LockedAddress` 那类直接声明在 `@Embeddable`
/// 字段上的 `@Column(updatable=false)`），覆盖出来的只读列没有任何断言——判定看不到覆盖就会把
/// `home_city` 写进 `SET`，条件更新则完全放行。
class JpaPersistOverrideReadOnlyTest extends JpaTestSupport {

    @BeforeEach
    void seedOverrideReadOnlyFlags() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from OverrideReadOnlyFlags").executeUpdate();
            entityManager.persist(new OverrideReadOnlyFlags(1L, "orig", new HomeAddress("orig-city", "orig-zone")));
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("实体 update：@AttributeOverride 覆盖出来的只读列不进 SET，同块可写叶子照常写")
    void updateSkipsOverrideReadOnlyLeaf() {
        inTx(entityManager -> {
            new JpaPersistExecutor<>(entityManager, meta(), OverrideReadOnlyFlags.class)
                    .update(new OverrideReadOnlyFlags(1L, "renamed", new HomeAddress("hacked-city", "hacked-zone")));
            return null;
        });

        List<String> sql = normalized(drainSql());
        assertThat(sql).hasSize(1);
        String update = sql.getFirst();
        assertThat(update).startsWith("update override_readonly_flags ");
        assertThat(update).contains("home_zone=?");
        assertThat(update).as("@AttributeOverride 覆盖出来的只读列不进 SET").doesNotContain("home_city");

        OverrideReadOnlyFlags read = readFlags();
        assertThat(read.getLabel()).isEqualTo("renamed");
        assertThat(read.getHome().getZone()).isEqualTo("hacked-zone");
        assertThat(read.getHome().getCity()).as("只读列的库内值不得被覆盖").isEqualTo("orig-city");
    }

    @Test
    @DisplayName("条件 update 写 @AttributeOverride 覆盖出来的只读列：UOE，文案点名生效列路径")
    void conditionalUpdateOfOverrideReadOnlyLeafIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> flags(entityManager)
                            .update()
                            .set(EntityPath.of(OverrideReadOnlyFlags::getHome).get(HomeAddress::getCity), "hacked")
                            .where(OverrideReadOnlyFlags::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("条件更新不支持 @Column(updatable=false) 列: " + OverrideReadOnlyFlags.class.getName()
                            + ".home.city");
            return null;
        });

        assertThat(readFlags().getHome().getCity()).as("被拒绝的语句不得落库").isEqualTo("orig-city");
    }

    @Test
    @DisplayName("条件 update 写同一块里的可写叶子：覆盖只作用于被覆盖的那一列")
    void conditionalUpdateOfWritableSiblingLeafStillWorks() {
        inTx(entityManager -> {
            flags(entityManager)
                    .update()
                    .set(EntityPath.of(OverrideReadOnlyFlags::getHome).get(HomeAddress::getZone), "hacked")
                    .where(OverrideReadOnlyFlags::getId)
                    .eq(1L)
                    .execute();
            return null;
        });

        List<String> sql = normalized(drainSql());
        assertThat(sql).hasSize(1);
        assertThat(sql.getFirst()).contains("home_zone=?").doesNotContain("home_city");

        OverrideReadOnlyFlags read = readFlags();
        assertThat(read.getHome().getZone()).isEqualTo("hacked");
        assertThat(read.getHome().getCity()).isEqualTo("orig-city");
    }

    private EntityOperations<OverrideReadOnlyFlags> flags(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(OverrideReadOnlyFlags.class);
    }

    private OverrideReadOnlyFlags readFlags() {
        return inTx(entityManager -> entityManager.find(OverrideReadOnlyFlags.class, 1L));
    }
}
