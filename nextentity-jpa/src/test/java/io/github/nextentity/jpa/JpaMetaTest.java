package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.jpa.entity.Article;
import io.github.nextentity.jpa.entity.BaseRecord;
import io.github.nextentity.jpa.entity.DerivedRecord;
import io.github.nextentity.jpa.entity.ElementCollectionHolder;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.entity.VersionedEntry;
import io.github.nextentity.jpa.entity.VersionedLedger;
import io.github.nextentity.jpa.meta.JpaEntityType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `JpaMeta.association` / `JpaMeta.pluralAssociation` 与 `JpaEntityAttribute.type` 的两侧。
///
/// 守护的变异体：
/// - `meta/JpaMeta.java` 的 `association` 里集合关联分支的 `return true` 改成 `false`。现有实体
///   只有 `@ManyToOne`（走 `SingularAttribute#isAssociation()`），`PluralAttribute` 分支零覆盖，
///   所以这里加一个 `@OneToMany` 属性（`VersionedLedger#entries`）。
/// - `meta/JpaMeta.java` 的 `pluralAssociation` 整个判定：它是 `JpaPaths` 拒绝集合关联路径的
///   唯一依据，恒真/恒假都会造成「过度拒绝」或「静默 join 出错数」，见
///   [JpaPluralAssociationRejectionTest]。
/// - `meta/JpaEntityAttribute.java:41`：`type()` 返回 `null`。属性类型此前没有任何调用点。
/// - `meta/JpaEntityAttribute.java:22`：`resolveField` 的循环只查实体自身、不向父类上溯。
///   现有实体的字段都声明在自己身上，继承映射这条路径零覆盖，所以这里用 `@MappedSuperclass`
///   声明字段（`DerivedRecord#code`）。
class JpaMetaTest extends JpaTestSupport {

    @Test
    @DisplayName("association：集合关联与单值关联为 true，普通列为 false")
    void associationDistinguishesAssociationFromColumn() {
        assertThat(meta().association(VersionedLedger.class, "entries")).isTrue();
        assertThat(meta().association(VersionedEntry.class, "ledger")).isTrue();
        assertThat(meta().association(VersionedLedger.class, "name")).isFalse();
        assertThat(meta().association(VersionedEntry.class, "label")).isFalse();

        // @ElementCollection 是 PluralAttribute 但 isAssociation() 为假：删掉 association 里
        // 「PluralAttribute 提前返回 true」那一行后，这里会变成 false——JpaPaths 随即把它当普通列
        // get()（渲染出错）、JpaPersistExecutor 把它当标量列 update.set(...)（写路径出错）。
        assertThat(meta().association(ElementCollectionHolder.class, "labels"))
                .as("@ElementCollection 必须按「可 join 的关联」处理，不能落回 isAssociation()")
                .isTrue();
        assertThat(meta().pluralAssociation(ElementCollectionHolder.class, "labels"))
                .as("@ElementCollection 同时是集合属性，两条判据必须分得开")
                .isTrue();

        // 与既有实体保持一致：普通外键列不 join，关联要 join
        assertThat(meta().association(ShopOrder.class, "customer")).isTrue();
        assertThat(meta().association(ShopOrder.class, "amount")).isFalse();
        assertThat(meta().association(ShopOrder.class, "version")).isFalse();
    }

    @Test
    @DisplayName("pluralAssociation：只有集合关联为 true，单值关联与普通列都为 false")
    void pluralAssociationDistinguishesCollectionFromSingularAssociation() {
        assertThat(meta().pluralAssociation(VersionedLedger.class, "entries")).isTrue();
        assertThat(meta().pluralAssociation(Article.class, "tags")).isTrue();

        // 反向控制：判据若写成恒真（或把 association 换成 pluralAssociation），上面两条照样绿，
        // 而 JpaPaths 会把每个 @ManyToOne 路径一并拒掉——所有嵌套路径查询集体失效。
        assertThat(meta().pluralAssociation(VersionedEntry.class, "ledger")).isFalse();
        assertThat(meta().pluralAssociation(ShopOrder.class, "customer")).isFalse();
        assertThat(meta().pluralAssociation(ShopOrder.class, "amount")).isFalse();

        // 两个判据必须分得开：同一属性 association=true、pluralAssociation=false
        assertThat(meta().association(ShopOrder.class, "customer")).isTrue();
    }

    @Test
    @DisplayName("entityType：同一实体多次调用返回同一实例，不同实体不共用实例")
    void entityTypeIsCachedPerEntity() {
        // JpaMeta 的 javadoc 承诺"同一实体多次调用返回同一实例"；不钉住它，缓存退化成
        // 每次重建也全绿——重建会连带丢掉 attributes() 里缓存的字段句柄，
        // 只是性能与身份语义的变化，任何结果断言都看不出来。
        JpaEntityType<ShopOrder> first = meta().entityType(ShopOrder.class);
        assertThat(meta().entityType(ShopOrder.class)).as("同一实体两次调用必须是同一实例").isSameAs(first);
        assertThat(meta().entityType(ShopOrder.class)).as("第三次调用仍是同一实例").isSameAs(first);
        // 反方向：缓存键写错（任意类型都命中同一个实例）时上面两条照样绿，
        // 所以必须同时钉住"不同实体拿到不同实例"。
        assertThat(meta().entityType(VersionedLedger.class))
                .as("不同实体不能共用元数据实例")
                .isNotSameAs(meta().entityType(ShopOrder.class));
    }

    @Test
    @DisplayName("attribute.type()：取的是字段声明类型")
    void attributeTypeComesFromField() {
        JpaEntityType<VersionedLedger> ledger = meta().entityType(VersionedLedger.class);

        assertThat(ledger.id().type()).isEqualTo(Long.class);
        assertThat(ledger.version().type()).isEqualTo(Long.class);
        assertThat(ledger.attributes().get("name").type()).isEqualTo(String.class);
        assertThat(ledger.attributes().get("entries").type()).isEqualTo(List.class);
    }

    @Test
    @DisplayName("继承属性：字段声明在 @MappedSuperclass 父类上，元数据仍能读写")
    void inheritedAttributeResolvesToParentField() throws NoSuchFieldException {
        JpaEntityType<DerivedRecord> entityType = meta().entityType(DerivedRecord.class);
        EntityAttribute code = entityType.attributes().get("code");

        assertThat(code).isNotNull();
        assertThat(code.name()).isEqualTo("code");
        assertThat(code.type()).isEqualTo(String.class);
        // 前提：子类自己没有声明 code，因此解析必须上溯到父类那一轮循环
        assertThatThrownBy(() -> DerivedRecord.class.getDeclaredField("code")).isInstanceOf(NoSuchFieldException.class);
        assertThat(BaseRecord.class.getDeclaredField("code")).isNotNull();

        DerivedRecord record = new DerivedRecord(1L, "R-1");
        assertThat(code.get(record)).isEqualTo("R-1");

        code.set(record, "R-2");
        assertThat(record.getCode()).isEqualTo("R-2");
    }
}
