package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.entity.VersionedEntry;
import io.github.nextentity.jpa.entity.VersionedLedger;
import io.github.nextentity.jpa.meta.JpaEntityType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `JpaMeta.association` 与 `JpaEntityAttribute.type` 的两侧。
///
/// 守护的变异体：
/// - `meta/JpaMeta.java:55`：集合关联分支的 `return true` 改成 `false`。现有实体只有
///   `@ManyToOne`（走 `SingularAttribute#isAssociation()`），`PluralAttribute` 分支零覆盖，
///   所以这里加一个 `@OneToMany` 属性（`VersionedLedger#entries`）。
/// - `meta/JpaEntityAttribute.java:41`：`type()` 返回 `null`。属性类型此前没有任何调用点。
class JpaMetaTest extends JpaTestSupport {

    @Test
    @DisplayName("association：集合关联与单值关联为 true，普通列为 false")
    void associationDistinguishesAssociationFromColumn() {
        assertThat(meta().association(VersionedLedger.class, "entries")).isTrue();
        assertThat(meta().association(VersionedEntry.class, "ledger")).isTrue();
        assertThat(meta().association(VersionedLedger.class, "name")).isFalse();
        assertThat(meta().association(VersionedEntry.class, "label")).isFalse();

        // 与既有实体保持一致：普通外键列不 join，关联要 join
        assertThat(meta().association(ShopOrder.class, "customer")).isTrue();
        assertThat(meta().association(ShopOrder.class, "amount")).isFalse();
        assertThat(meta().association(ShopOrder.class, "version")).isFalse();
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
}
