package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.jpa.entity.AddressDetail;
import io.github.nextentity.jpa.entity.EmbeddedFlags;
import io.github.nextentity.jpa.entity.LockedAddress;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 实体写路径（`JpaPersistExecutor.setValues`）对 `@Embedded` 的写侧裁剪：按**叶子列**判可写，
/// `@Column(updatable=false)` 的嵌入叶子不进 `SET`。
///
/// 3.1.0 只修了顶层标量/关联列：整块 `set(root.get(inner), 可嵌入对象)` 会被 Hibernate 展开成全部
/// 嵌入列，只读叶子跟着被写（与 JDBC 后端 `WriteSql` 跳过该列的行为相反）。
class JpaPersistEmbeddedReadOnlyTest extends JpaTestSupport {

    @BeforeEach
    void seedEmbeddedFlags() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from EmbeddedFlags").executeUpdate();
            entityManager.persist(new EmbeddedFlags(
                    1L, "flag", new LockedAddress("Hangzhou", "orig", new AddressDetail("code", "secret"))));
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("实体 update：嵌入内部只读叶子不进 SET，可写叶子（含二级嵌入）照常写")
    void updateSkipsEmbeddedReadOnlyLeaf() {
        inTx(entityManager -> {
            new JpaPersistExecutor<>(entityManager, meta(), EmbeddedFlags.class)
                    .update(new EmbeddedFlags(
                            1L,
                            "renamed",
                            new LockedAddress("Ningbo", "hacked", new AddressDetail("code2", "secret2"))));
            return null;
        });

        List<String> sql = normalized(drainSql());
        assertThat(sql).hasSize(1);
        String update = sql.getFirst();
        assertThat(update).startsWith("update embedded_flags ");
        assertThat(update).contains("label=?").contains("city=?").contains("detail_code=?");
        assertThat(update).as("@Embedded 内部的 @Column(updatable=false) 叶子不进 SET").doesNotContain("locked_code");
        assertThat(update).as("二级 @Embedded 内部的只读叶子同样不进 SET").doesNotContain("detail_secret");

        EmbeddedFlags read = readFlags();
        assertThat(read.getLabel()).isEqualTo("renamed");
        assertThat(read.getAddress().getCity()).isEqualTo("Ningbo");
        assertThat(read.getAddress().getLockedCode()).as("只读叶子的库内值不得被覆盖").isEqualTo("orig");
        assertThat(read.getAddress().getDetail().getCode()).isEqualTo("code2");
        assertThat(read.getAddress().getDetail().getSecret()).isEqualTo("secret");
    }

    @Test
    @DisplayName("实体 update：嵌入对象整体为 null 时，可写叶子写 NULL，只读叶子仍不动")
    void updateWithNullEmbeddedWritesNullForWritableLeaves() {
        inTx(entityManager -> {
            new JpaPersistExecutor<>(entityManager, meta(), EmbeddedFlags.class)
                    .update(new EmbeddedFlags(1L, "renamed", null));
            return null;
        });

        List<String> sql = normalized(drainSql());
        assertThat(sql).hasSize(1);
        assertThat(sql.getFirst()).as("整块置空按可写叶子逐列写 NULL").contains("city=?").contains("detail_code=?");
        assertThat(sql.getFirst()).as("只读叶子不进 SET").doesNotContain("locked_code");

        EmbeddedFlags read = readFlags();
        assertThat(read.getAddress().getCity()).isNull();
        assertThat(read.getAddress().getDetail().getCode()).isNull();
        assertThat(read.getAddress().getLockedCode()).as("只读叶子的库内值不得被覆盖").isEqualTo("orig");
    }

    @Test
    @DisplayName("托管实体 update：走 flush，只读叶子由 Hibernate 按映射剔除，可写叶子照常写")
    void managedUpdateSkipsEmbeddedReadOnlyLeaf() {
        inTx(entityManager -> {
            EmbeddedFlags managed = entityManager.find(EmbeddedFlags.class, 1L);
            managed.setLabel("renamed");
            managed.setAddress(new LockedAddress("Suzhou", "hacked", new AddressDetail("code2", "secret2")));
            new JpaPersistExecutor<>(entityManager, meta(), EmbeddedFlags.class).update(managed);
            return null;
        });

        EmbeddedFlags read = readFlags();
        assertThat(read.getLabel()).isEqualTo("renamed");
        assertThat(read.getAddress().getCity()).isEqualTo("Suzhou");
        assertThat(read.getAddress().getLockedCode())
                .as("托管路径靠 Hibernate 的 updatable=false 剔除，不该被 CriteriaUpdate 破掉")
                .isEqualTo("orig");
    }

    private EmbeddedFlags readFlags() {
        return inTx(entityManager -> entityManager.find(EmbeddedFlags.class, 1L));
    }
}
