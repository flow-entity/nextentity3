package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.jpa.entity.Organization;
import io.github.nextentity.jpa.entity.Seal;
import io.github.nextentity.jpa.entity.UpdatableFlags;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 实体写路径（`JpaPersistExecutor.setValues`）的写侧裁剪：`@Column(updatable=false)` 的标量列与
/// `@JoinColumn(updatable=false)` 的 `@ManyToOne` / `@OneToOne` 外键列都不进 `SET`。
///
/// 与 JDBC 的 `WriteSql` 同口径：只按 flags 判，不做列名去重。
class JpaPersistReadOnlyColumnsTest extends JpaTestSupport {

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
    @DisplayName("实体 update：只读标量列与只读外键列都不进 SET，可写列照常写")
    void updateSkipsReadOnlyColumns() {
        inTx(entityManager -> {
            new JpaPersistExecutor<>(entityManager, meta(), UpdatableFlags.class)
                    .update(new UpdatableFlags(
                            1L,
                            "renamed",
                            "hacked",
                            entityManager.getReference(Organization.class, 2L),
                            entityManager.getReference(Organization.class, 2L),
                            entityManager.getReference(Seal.class, 2L)));
            return null;
        });

        List<String> sql = normalized(drainSql());
        assertThat(sql).hasSize(1);
        String update = sql.getFirst();
        assertThat(update).startsWith("update updatable_flags ");
        assertThat(update).contains("name=?").contains("owner_id=?");
        assertThat(update).as("@Column(updatable=false) 的标量列不进 SET").doesNotContain("locked_name");
        assertThat(update)
                .as("@JoinColumn(updatable=false) 的 @ManyToOne 外键不进 SET")
                .doesNotContain("locked_owner_id");
        assertThat(update)
                .as("@JoinColumn(updatable=false) 的 @OneToOne 外键不进 SET")
                .doesNotContain("locked_seal_id");
        assertThat(update).contains("where");
    }
}
