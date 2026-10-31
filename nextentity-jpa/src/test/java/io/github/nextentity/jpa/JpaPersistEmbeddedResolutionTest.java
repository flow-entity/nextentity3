package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jpa.entity.ContactInfo;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.AllEmbeddedReadOnly;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.AllLockedEmbeddable;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.DeepContact;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.DeepEmbeddedAssociationFlags;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.DeepOwnerRef;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.NestedInnerCode;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.NestedOnlyWritableInside;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.NestedOuterReadOnly;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.OverrideNameMismatchFlags;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.PlainContact;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.PlainEmbeddedFlags;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.UnannotatedEmbeddedAssociation;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.UnannotatedOwnerRef;
import io.github.nextentity.jpa.entity.Organization;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 写侧「生效列解析」的补强用例：`JpaMeta.embeddedColumn` / `embeddedJoinColumn` 在
/// **叶子没有列注解**、**关联没有 `@JoinColumn`**、**二级嵌入里放关联**三种形状下的行为。
///
/// 这三种形状此前没有任何用例：解析的「覆盖没命中 → 回落到叶子自己的注解」和「点号路径递归」
/// 两段只能靠读代码判断，写侧裁剪到底按哪一列判可写也就没有证据。
class JpaPersistEmbeddedResolutionTest extends JpaTestSupport {

    @Test
    @DisplayName("实体 update：嵌入叶子没有任何列注解时按可写处理，列照常进 SET")
    void updateWritesLeafWithoutColumnAnnotations() {
        inTx(entityManager -> {
            entityManager.createNativeQuery("delete from plain_embedded_flags").executeUpdate();
            entityManager.persist(new PlainEmbeddedFlags(1L, new PlainContact("Hangzhou", "310000")));
            return null;
        });
        drainSql();

        inTx(entityManager -> {
            new JpaPersistExecutor<>(entityManager, meta(), PlainEmbeddedFlags.class)
                    .update(new PlainEmbeddedFlags(1L, new PlainContact("Ningbo", "315000")));
            return null;
        });

        List<String> sql = normalized(drainSql());
        assertThat(sql).hasSize(1);
        String update = sql.getFirst();
        assertThat(update).startsWith("update plain_embedded_flags ");
        assertThat(update).as("没有列注解的嵌入叶子按默认可写处理").contains("city=?").contains("zip=?");
    }

    @Test
    @DisplayName("实体 update：嵌入里的关联没有 @JoinColumn 时按默认外键列写")
    void updateWritesAssociationWithoutJoinColumn() {
        inTx(entityManager -> {
            entityManager
                    .createNativeQuery("delete from unannotated_embedded_association")
                    .executeUpdate();
            entityManager.persist(new UnannotatedEmbeddedAssociation(
                    1L, new UnannotatedOwnerRef(entityManager.getReference(Organization.class, 1L))));
            return null;
        });
        drainSql();

        inTx(entityManager -> {
            new JpaPersistExecutor<>(entityManager, meta(), UnannotatedEmbeddedAssociation.class)
                    .update(new UnannotatedEmbeddedAssociation(
                            1L, new UnannotatedOwnerRef(entityManager.getReference(Organization.class, 2L))));
            return null;
        });

        List<String> sql = normalized(drainSql());
        assertThat(sql).hasSize(1);
        String update = sql.getFirst();
        assertThat(update).startsWith("update unannotated_embedded_association ");
        assertThat(update).as("缺 @JoinColumn 的嵌入关联按默认外键列写").contains("owner_id=?");
    }

    @Test
    @DisplayName("实体 update：二级嵌入里的只读外键不进 SET，同块可写列照常写")
    void updateSkipsDeepEmbeddedReadOnlyAssociation() {
        inTx(entityManager -> {
            entityManager
                    .createNativeQuery("delete from deep_embedded_association_flags")
                    .executeUpdate();
            entityManager.persist(new DeepEmbeddedAssociationFlags(
                    1L, new DeepContact("orig", new DeepOwnerRef(entityManager.getReference(Organization.class, 1L)))));
            return null;
        });
        drainSql();

        inTx(entityManager -> {
            new JpaPersistExecutor<>(entityManager, meta(), DeepEmbeddedAssociationFlags.class)
                    .update(new DeepEmbeddedAssociationFlags(
                            1L,
                            new DeepContact(
                                    "renamed", new DeepOwnerRef(entityManager.getReference(Organization.class, 2L)))));
            return null;
        });

        List<String> sql = normalized(drainSql());
        assertThat(sql).hasSize(1);
        String update = sql.getFirst();
        assertThat(update).startsWith("update deep_embedded_association_flags ");
        assertThat(update).contains("deep_code=?");
        assertThat(update).as("二级嵌入里的 @JoinColumn(updatable=false) 外键不进 SET").doesNotContain("deep_owner_id");
    }

    @Test
    @DisplayName("实体 update：可写列只在二级嵌入里时不算「没有可写列」")
    void updateKeepsNestedWritableLeafWhenOuterLeavesAreReadOnly() {
        inTx(entityManager -> {
            entityManager
                    .createNativeQuery("delete from nested_only_writable_inside")
                    .executeUpdate();
            entityManager.persist(
                    new NestedOnlyWritableInside(1L, new NestedOuterReadOnly("orig", new NestedInnerCode("a"))));
            return null;
        });
        drainSql();

        inTx(entityManager -> {
            new JpaPersistExecutor<>(entityManager, meta(), NestedOnlyWritableInside.class)
                    .update(new NestedOnlyWritableInside(
                            1L, new NestedOuterReadOnly("hacked", new NestedInnerCode("b"))));
            return null;
        });

        List<String> sql = normalized(drainSql());
        assertThat(sql).hasSize(1);
        String update = sql.getFirst();
        assertThat(update).startsWith("update nested_only_writable_inside ");
        assertThat(update).as("外层全只读、内层可写时仍有 SET 列").contains("inner_code=?");
        assertThat(update).as("外层只读列不进 SET").doesNotContain("outer_locked");
    }

    @Test
    @DisplayName("实体 update：@AssociationOverride 名字不匹配时不生效，按叶子自己的外键列写")
    void updateIgnoresAssociationOverrideWithDifferentName() {
        inTx(entityManager -> {
            entityManager
                    .createNativeQuery("delete from override_name_mismatch_flags")
                    .executeUpdate();
            entityManager.persist(new OverrideNameMismatchFlags(
                    1L,
                    new ContactInfo(
                            "Hangzhou",
                            entityManager.getReference(Organization.class, 1L),
                            entityManager.getReference(Organization.class, 1L))));
            return null;
        });
        drainSql();

        inTx(entityManager -> {
            new JpaPersistExecutor<>(entityManager, meta(), OverrideNameMismatchFlags.class)
                    .update(new OverrideNameMismatchFlags(
                            1L,
                            new ContactInfo(
                                    "Ningbo",
                                    entityManager.getReference(Organization.class, 2L),
                                    entityManager.getReference(Organization.class, 2L))));
            return null;
        });

        List<String> sql = normalized(drainSql());
        assertThat(sql).hasSize(1);
        String update = sql.getFirst();
        assertThat(update).startsWith("update override_name_mismatch_flags ");
        assertThat(update)
                .as("覆盖名字只对 lockedCountry 生效，country 仍按自己的 @JoinColumn 写")
                .contains("country_id=?");
        assertThat(update).doesNotContain("mismatch_locked_id");
    }

    @Test
    @DisplayName("实体 update：嵌入叶子全部只读时显式拒绝，不把空 SET 留给 provider")
    void updateRejectsEntityWhoseEmbeddedLeavesAreAllReadOnly() {
        inTx(entityManager -> {
            entityManager
                    .createNativeQuery("delete from all_embedded_read_only")
                    .executeUpdate();
            entityManager.persist(new AllEmbeddedReadOnly(1L, new AllLockedEmbeddable("orig")));
            return null;
        });
        drainSql();

        assertThatThrownBy(() -> inTx(entityManager -> {
                    new JpaPersistExecutor<>(entityManager, meta(), AllEmbeddedReadOnly.class)
                            .update(new AllEmbeddedReadOnly(1L, new AllLockedEmbeddable("hacked")));
                    return null;
                }))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("没有可写标量列");
    }
}
