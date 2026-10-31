package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jpa.entity.EmbeddedFlags;
import io.github.nextentity.jpa.entity.LockedAddress;
import io.github.nextentity.jpa.entity.Organization;
import io.github.nextentity.jpa.entity.UnannotatedLink;
import io.github.nextentity.jpa.entity.UpdatableFlags;
import io.github.nextentity.jpa.meta.JpaMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `JpaMeta` 写侧判定的元数据契约：`@Embedded` 识别、按 `@Column` / `@JoinColumn` 判可写、
/// `@Id` 属性名、属性类型与字段读取。
///
/// 这些查询是「形态判定」与「可写性判定」的唯一依据，所以逐条钉住正反例；不存在属性时按可写
/// 处理（存在性校验留给写侧取路径的那一步）。
class JpaMetaAttributesTest extends JpaTestSupport {

    @Test
    @DisplayName("@Embedded 识别：可嵌入属性 true，标量与关联 false")
    void embeddedDetection() {
        JpaMeta meta = meta();
        assertThat(meta.embedded(EmbeddedFlags.class, "address")).isTrue();
        assertThat(meta.embedded(LockedAddress.class, "detail"))
                .as("二级 @Embedded")
                .isTrue();
        assertThat(meta.embedded(EmbeddedFlags.class, "label")).isFalse();
        assertThat(meta.embedded(UpdatableFlags.class, "owner")).isFalse();
        assertThat(meta.embedded(UpdatableFlags.class, "noSuchAttribute"))
                .as("不存在的属性没有列可写，按非嵌入处理")
                .isFalse();
    }

    @Test
    @DisplayName("标量可写性：@Column(updatable=false) false，其余 true")
    void scalarUpdatableContract() {
        JpaMeta meta = meta();
        assertThat(meta.scalarUpdatable(UpdatableFlags.class, "lockedName")).isFalse();
        assertThat(meta.scalarUpdatable(UpdatableFlags.class, "name")).isTrue();
        assertThat(meta.scalarUpdatable(LockedAddress.class, "lockedCode"))
                .as("嵌入内部的只读叶子")
                .isFalse();
        assertThat(meta.scalarUpdatable(UpdatableFlags.class, "noSuchAttribute"))
                .as("不存在的属性按可写处理")
                .isTrue();
    }

    @Test
    @DisplayName("外键可写性：@JoinColumn(updatable=false) false，其余 true")
    void foreignKeyUpdatableContract() {
        JpaMeta meta = meta();
        assertThat(meta.foreignKeyUpdatable(UpdatableFlags.class, "lockedOwner"))
                .isFalse();
        assertThat(meta.foreignKeyUpdatable(UpdatableFlags.class, "owner")).isTrue();
        assertThat(meta.foreignKeyUpdatable(UpdatableFlags.class, "noSuchAttribute"))
                .as("不存在的属性按可写处理")
                .isTrue();
        assertThat(meta.foreignKeyUpdatable(UnannotatedLink.class, "owner"))
                .as("缺 @JoinColumn 时按 JPA 默认外键命名推断，仍按可写处理")
                .isTrue();
    }

    @Test
    @DisplayName("@Id 属性名从元模型取；非实体类型是显式拒绝")
    void idNameContract() {
        JpaMeta meta = meta();
        assertThat(meta.idName(UpdatableFlags.class)).isEqualTo("id");
        assertThatThrownBy(() -> meta.idName(LockedAddress.class))
                .as("可嵌入类型不是实体，元模型直接拒绝")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("属性 Java 类型与属性名清单；悬空属性名是显式拒绝")
    void attributeTypeAndNamesContract() {
        JpaMeta meta = meta();
        assertThat(meta.attributeJavaType(UpdatableFlags.class, "lockedOwner")).isEqualTo(Organization.class);
        assertThat(meta.attributeJavaType(EmbeddedFlags.class, "address")).isEqualTo(LockedAddress.class);
        assertThat(meta.attributeNames(EmbeddedFlags.class)).containsExactly("id", "address", "label");
        assertThatThrownBy(() -> meta.attributeJavaType(UpdatableFlags.class, "noSuchAttribute"))
                .as("消息要点名是哪个类型上没有这个属性")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("找不到属性")
                .hasMessageContaining(UpdatableFlags.class.getName());
    }

    @Test
    @DisplayName("字段读取：null 持有者返回 null，悬空属性名是显式拒绝")
    void readContract() {
        JpaMeta meta = meta();
        LockedAddress address = new LockedAddress("Hangzhou", "orig", null);
        assertThat(meta.read(LockedAddress.class, "city", address)).isEqualTo("Hangzhou");
        assertThat(meta.read(LockedAddress.class, "city", null))
                .as("嵌入对象整体为 null 时叶子读不到值")
                .isNull();
        assertThatThrownBy(() -> meta.read(LockedAddress.class, "noSuchAttribute", address))
                .as("消息要点名是哪个类型上没有这个字段")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("找不到可读字段")
                .hasMessageContaining(LockedAddress.class.getName());
    }
}
