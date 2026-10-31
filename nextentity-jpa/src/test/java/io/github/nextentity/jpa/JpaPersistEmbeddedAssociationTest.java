package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.AddressBook;
import io.github.nextentity.jpa.entity.ContactInfo;
import io.github.nextentity.jpa.entity.EmbeddedAssociationFlags;
import io.github.nextentity.jpa.entity.Organization;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `@Embedded` 里的单值关联：写侧必须按**关联**处理（按 `@Id` 写本表外键列、只读外键跳过），
/// 而不是当成一列标量；条件更新的 SET 目标白名单也不含「嵌入属性.关联对象」。
///
/// 覆盖两条容易漏的口径：只读外键可以声明在 `@Embeddable` 字段上（`@JoinColumn(updatable=false)`），
/// 也可以由实体侧 `@AssociationOverride(joinColumns = @JoinColumn(updatable=false))` 覆盖出来。
class JpaPersistEmbeddedAssociationTest extends JpaTestSupport {

    @BeforeEach
    void seedEmbeddedAssociationFlags() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from EmbeddedAssociationFlags").executeUpdate();
            Organization first = entityManager.getReference(Organization.class, 1L);
            entityManager.persist(new EmbeddedAssociationFlags(
                    1L, "orig", new ContactInfo("Hangzhou", first, first), new AddressBook(first)));
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("实体 update：嵌入里的可写关联按 @Id 写外键，只读外键（含 @AssociationOverride 覆盖的）跳过")
    void updateWritesEmbeddedAssociationAndSkipsReadOnlyOne() {
        inTx(entityManager -> {
            Organization second = entityManager.getReference(Organization.class, 2L);
            new JpaPersistExecutor<>(entityManager, meta(), EmbeddedAssociationFlags.class)
                    .update(new EmbeddedAssociationFlags(
                            1L, "renamed", new ContactInfo("Ningbo", second, second), new AddressBook(second)));
            return null;
        });

        List<String> sql = normalized(drainSql());
        assertThat(sql).hasSize(1);
        String update = sql.getFirst();
        assertThat(update).startsWith("update embedded_association_flags ");
        assertThat(update).as("嵌入里的可写关联写本表外键列").contains("country_id=?").contains("city=?");
        assertThat(update).as("嵌入里 @JoinColumn(updatable=false) 的外键不进 SET").doesNotContain("locked_country_id");
        assertThat(update).as("@AssociationOverride 覆盖出来的只读外键不进 SET").doesNotContain("billing_country_id");

        EmbeddedAssociationFlags read = readFlags();
        assertThat(read.getLabel()).isEqualTo("renamed");
        assertThat(read.getContact().getCity()).isEqualTo("Ningbo");
        assertThat(read.getContact().getCountry().getId()).isEqualTo(2L);
        assertThat(read.getContact().getLockedCountry().getId())
                .as("只读外键的库内值不得被覆盖")
                .isEqualTo(1L);
        assertThat(read.getBilling().getCountry().getId())
                .as("覆盖出来的只读外键的库内值不得被覆盖")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("条件 update 的 SET 目标不能是「嵌入属性.关联对象」：白名单显式拒绝，不得当标量写")
    void conditionalUpdateOfEmbeddedAssociationIsRejected() {
        inTx(entityManager -> {
            Organization second = entityManager.getReference(Organization.class, 2L);
            assertThatThrownBy(() -> flags(entityManager)
                            .update()
                            .set(
                                    EntityPath.of(EmbeddedAssociationFlags::getContact)
                                            .get(ContactInfo::getCountry),
                                    second)
                            .where(EmbeddedAssociationFlags::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("条件更新 SET 目标只能是本表列或关联.id: " + EmbeddedAssociationFlags.class.getName()
                            + ".contact.country");
            return null;
        });

        assertThat(readFlags().getContact().getCountry().getId())
                .as("被拒绝的语句不得落库")
                .isEqualTo(1L);
    }

    private EntityOperations<EmbeddedAssociationFlags> flags(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(EmbeddedAssociationFlags.class);
    }

    private EmbeddedAssociationFlags readFlags() {
        return inTx(entityManager -> entityManager.find(EmbeddedAssociationFlags.class, 1L));
    }
}
