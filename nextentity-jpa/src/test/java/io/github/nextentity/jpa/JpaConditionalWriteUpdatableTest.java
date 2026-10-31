package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.AddressDetail;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.EmbeddedFlags;
import io.github.nextentity.jpa.entity.LockedAddress;
import io.github.nextentity.jpa.entity.Organization;
import io.github.nextentity.jpa.entity.Seal;
import io.github.nextentity.jpa.entity.SealHolder;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.entity.UpdatableFlags;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 条件更新的 SET 目标契约：只有「本表列」与「关联.`@Id`（写本表外键列）」两种形态可写。
///
/// 只读列文案与 JDBC 后端**逐字一致**——同一调用打到两个后端要拿到同一句话，否则一致性只能
/// 断到"都抛了 UOE"这种弱结论；非法形态（关联对象 / 嵌入对象整体、被关联表的列、三段及以上）
/// 是另一条独立文案，因为它不是列标志问题而是路径语义问题。
///
/// 正向对照是必须的：只测拒绝的话，把「拒绝一切 SET」写成实现也能全绿。
class JpaConditionalWriteUpdatableTest extends JpaTestSupport {

    @BeforeEach
    void seedFlags() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from UpdatableFlags").executeUpdate();
            entityManager.createQuery("delete from EmbeddedFlags").executeUpdate();
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
            entityManager.persist(new EmbeddedFlags(
                    1L, "flag", new LockedAddress("Hangzhou", "orig", new AddressDetail("code", "secret"))));
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("条件 update 写 @Column(updatable=false) 标量列：UOE，文案与 JDBC 逐字一致，且不落库")
    void scalarUpdateOfReadOnlyColumnIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> flags(entityManager)
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
    @DisplayName("条件 update 用「关联.id」写 @JoinColumn(updatable=false) 外键列：UOE，不得绕过嵌套形态")
    void nestedForeignKeyUpdateOfReadOnlyJoinColumnIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> flags(entityManager)
                            .update()
                            .set(EntityPath.of(UpdatableFlags::getLockedOwner).get(Organization::getId), 2L)
                            .where(UpdatableFlags::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("条件更新不支持 @Column(updatable=false) 列: " + UpdatableFlags.class.getName()
                            + ".lockedOwner.id");
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
    @DisplayName("条件 update 用「关联.id」写 @OneToOne 的 @JoinColumn(updatable=false) 外键列：同样 UOE")
    void oneToOneNestedForeignKeyUpdateOfReadOnlyJoinColumnIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> flags(entityManager)
                            .update()
                            .set(EntityPath.of(UpdatableFlags::getLockedSeal).get(Seal::getId), 2L)
                            .where(UpdatableFlags::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage(
                            "条件更新不支持 @Column(updatable=false) 列: " + UpdatableFlags.class.getName() + ".lockedSeal.id");
            return null;
        });

        assertThat(readFlags().getLockedSeal().getId()).as("被拒绝的语句不得落库").isEqualTo(1L);
    }

    @Test
    @DisplayName("条件 update 的 SET 目标不能走关联反侧（mappedBy）：外键不在本表，独立文案拒绝")
    void inverseAssociationForeignKeyUpdateIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> seals(entityManager)
                            .update()
                            .set(EntityPath.of(Seal::getHolder).get(SealHolder::getId), 1L)
                            .where(Seal::getId)
                            .eq(1L)
                            .execute())
                    .as("反侧关联的外键列在 seal_holder 表上，不是本表可写列")
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("条件更新 SET 目标只能是本表列或关联.id: " + Seal.class.getName() + ".holder.id");
            return null;
        });
    }

    @Test
    @DisplayName("条件 update 的 SET 目标不能是关联对象整体（可写外键也一样）：独立文案")
    void associationObjectUpdateIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> flags(entityManager)
                            .update()
                            .set(EntityPath.of(UpdatableFlags::getOwner), new Organization(2L, "Empty"))
                            .where(UpdatableFlags::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("条件更新 SET 目标只能是本表列或关联.id: " + UpdatableFlags.class.getName() + ".owner");
            return null;
        });
    }

    @Test
    @DisplayName("条件 update 不能写被关联表的列（owner.name）：独立文案，且根表同名列不被写")
    void crossEntityColumnUpdateIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> customers(entityManager)
                            .update()
                            .set(EntityPath.of(Customer::getOrg).get(Organization::getName), "Renamed")
                            .where(Customer::getId)
                            .eq(10L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("条件更新 SET 目标只能是本表列或关联.id: " + Customer.class.getName() + ".org.name");
            return null;
        });

        inTx(entityManager -> {
            assertThat(entityManager.find(Customer.class, 10L).getName())
                    .as("Customer 自己也有 name 列，早先的实现会静默改写它")
                    .isEqualTo("Alice");
            assertThat(entityManager.find(Organization.class, 1L).getName())
                    .as("被关联表的列本来就不该被本表 UPDATE 写")
                    .isEqualTo("Acme");
            return null;
        });
    }

    @Test
    @DisplayName("条件 update 的 SET 目标不能是可嵌入对象整体：独立文案")
    void embeddedObjectUpdateIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> embeddedFlags(entityManager)
                            .update()
                            .set(
                                    EntityPath.of(EmbeddedFlags::getAddress),
                                    new LockedAddress("Ningbo", "hacked", new AddressDetail("code", "secret")))
                            .where(EmbeddedFlags::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("条件更新 SET 目标只能是本表列或关联.id: " + EmbeddedFlags.class.getName() + ".address");
            return null;
        });

        assertThat(readEmbeddedFlags().getAddress().getLockedCode())
                .as("整块赋值被拒后库内只读叶子不变")
                .isEqualTo("orig");
    }

    @Test
    @DisplayName("条件 update 写 @Embedded 内部的只读叶子：UOE，文案与 JDBC 逐字一致")
    void embeddedReadOnlyLeafUpdateIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> embeddedFlags(entityManager)
                            .update()
                            .set(EntityPath.of(EmbeddedFlags::getAddress).get(LockedAddress::getLockedCode), "hacked")
                            .where(EmbeddedFlags::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("条件更新不支持 @Column(updatable=false) 列: " + EmbeddedFlags.class.getName()
                            + ".address.lockedCode");
            return null;
        });

        assertThat(readEmbeddedFlags().getAddress().getLockedCode())
                .as("被拒绝的语句不得落库")
                .isEqualTo("orig");
    }

    @Test
    @DisplayName("条件 update 写可写的 @Embedded 叶子：照常写，只读叶子不动")
    void writableEmbeddedLeafStillUpdates() {
        int affected = inTx(entityManager -> embeddedFlags(entityManager)
                .update()
                .set(EntityPath.of(EmbeddedFlags::getAddress).get(LockedAddress::getCity), "Ningbo")
                .where(EmbeddedFlags::getId)
                .eq(1L)
                .execute());

        assertThat(affected).isEqualTo(1);
        LockedAddress address = readEmbeddedFlags().getAddress();
        assertThat(address.getCity()).isEqualTo("Ningbo");
        assertThat(address.getLockedCode()).isEqualTo("orig");
        assertThat(address.getDetail().getCode()).isEqualTo("code");
    }

    @Test
    @DisplayName("嵌入对象整体（两段路径）不是 set 目标：Hibernate 会展开全部嵌入列，显式拒绝")
    void nestedEmbeddedObjectUpdateIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> embeddedFlags(entityManager)
                            .update()
                            .set(
                                    EntityPath.of(EmbeddedFlags::getAddress).get(LockedAddress::getDetail),
                                    new AddressDetail("hacked", "hacked"))
                            .where(EmbeddedFlags::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("条件更新 SET 目标只能是本表列或关联.id: " + EmbeddedFlags.class.getName() + ".address.detail");
            return null;
        });

        AddressDetail detail = readEmbeddedFlags().getAddress().getDetail();
        assertThat(detail.getCode()).isEqualTo("code");
        assertThat(detail.getSecret()).isEqualTo("secret");
    }

    @Test
    @DisplayName("条件 update 的 SET 目标不支持三段路径（嵌套 @Embedded 的叶子）：独立文案")
    void nestedEmbeddedLeafUpdateIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> embeddedFlags(entityManager)
                            .update()
                            .set(
                                    EntityPath.of(EmbeddedFlags::getAddress)
                                            .get(LockedAddress::getDetail)
                                            .get(AddressDetail::getCode),
                                    "hacked")
                            .where(EmbeddedFlags::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("条件更新 SET 目标只能是本表列或关联.id: " + EmbeddedFlags.class.getName() + ".address.detail.code");
            return null;
        });

        assertThat(readEmbeddedFlags().getAddress().getDetail().getCode()).isEqualTo("code");
    }

    @Test
    @DisplayName("元数据契约：不存在的属性没有列可写，按可更新处理（存在性校验留给 get 的那一步）")
    void unknownAttributeIsTreatedAsUpdatable() {
        assertThat(meta().entityType(UpdatableFlags.class).updatable("noSuchAttribute"))
                .isTrue();
    }

    @Test
    @DisplayName("正向：可更新的标量列与「关联.id」外键列照常写，只读列不受影响")
    void writableColumnsStillUpdate() {
        int affected = inTx(entityManager -> flags(entityManager)
                .update()
                .set(Path.of(UpdatableFlags::getName), "renamed")
                .set(EntityPath.of(UpdatableFlags::getOwner).get(Organization::getId), 2L)
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
    @DisplayName("正向：条件 update 可以把外键置空（写 NULL 而不是跳过列）")
    void foreignKeyCanBeNulled() {
        int affected = inTx(entityManager -> orders(entityManager)
                .update()
                .set(EntityPath.of(ShopOrder::getCustomer).get(Customer::getId), null)
                .where(ShopOrder::getId)
                .eq(100L)
                .execute());

        assertThat(affected).isEqualTo(1);
        inTx(entityManager -> {
            assertThat(entityManager.find(ShopOrder.class, 100L).getCustomer()).isNull();
            return null;
        });
    }

    @Test
    @DisplayName("条件 delete 不受只读列影响：只读列只在 SET 侧被裁剪")
    void deleteIgnoresReadOnlyColumns() {
        int affected = inTx(entityManager -> flags(entityManager)
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

    private EmbeddedFlags readEmbeddedFlags() {
        return inTx(entityManager -> entityManager.find(EmbeddedFlags.class, 1L));
    }

    private EntityOperations<UpdatableFlags> flags(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(UpdatableFlags.class);
    }

    private EntityOperations<EmbeddedFlags> embeddedFlags(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(EmbeddedFlags.class);
    }

    private EntityOperations<Customer> customers(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(Customer.class);
    }

    private EntityOperations<ShopOrder> orders(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(ShopOrder.class);
    }

    private EntityOperations<Seal> seals(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(Seal.class);
    }
}
