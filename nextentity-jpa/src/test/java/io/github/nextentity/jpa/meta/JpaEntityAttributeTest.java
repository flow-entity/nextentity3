package io.github.nextentity.jpa.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jpa.entity.DerivedRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `JpaEntityAttribute.resolveField`（`meta/JpaEntityAttribute.java:21-32`）的两侧。
///
/// 放在 `io.github.nextentity.jpa.meta` 包内是因为被测类的构造器是包私有的：`JpaMeta.entityType`
/// 只会拿元模型里声明过的属性名来建属性句柄，而元模型里的属性名总能解析到字段——也就是说
/// 「整条继承链上都找不到字段」这条护栏没有公开调用路径。这里直接构造是为了钉住它的**拒绝文案**
/// （`IllegalArgumentException` + 完整消息），不是绕过封装去测内部实现细节。
///
/// 不需要 Hibernate：这两条用例只打反射字段解析，与元模型无关；元模型一侧的同一分支由
/// `JpaMetaTest#inheritedAttributeResolvesToParentField` 走真实 Hibernate 覆盖。
class JpaEntityAttributeTest {

    @Test
    @DisplayName("继承来的属性：子类没有该字段，沿父类找到并读写父类字段")
    void resolvesFieldDeclaredOnSuperclass() {
        JpaEntityAttribute code = new JpaEntityAttribute(DerivedRecord.class, "code");
        DerivedRecord record = new DerivedRecord(7L, "D-7");

        assertThat(code.name()).isEqualTo("code");
        assertThat(code.type()).isEqualTo(String.class);
        assertThat(code.get(record)).isEqualTo("D-7");

        code.set(record, "D-8");
        assertThat(record.getCode()).isEqualTo("D-8");
    }

    @Test
    @DisplayName("set 收到 null 实体：NullPointerException，消息点名 entity")
    void setRejectsNullEntity() {
        JpaEntityAttribute code = new JpaEntityAttribute(DerivedRecord.class, "code");

        // 去掉 `Objects.requireNonNull(entity, "entity")` 之后 `field.set(null, …)` 一样抛
        // NullPointerException，只断类型分不清这两条路径；消息里的 "entity" 只有那条护栏会写
        assertThatThrownBy(() -> code.set(null, "D-9"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entity");
    }

    @Test
    @DisplayName("整条继承链都没有该字段：IllegalArgumentException，消息点名实体与字段")
    void rejectsUnknownFieldOnWholeInheritanceChain() {
        assertThatThrownBy(() -> new JpaEntityAttribute(DerivedRecord.class, "missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("实体 " + DerivedRecord.class.getName() + " 上找不到字段 missing");
    }
}
