package io.github.nextentity.jpa.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jpa.entity.DerivedRecord;
import io.github.nextentity.jpa.entity.LabelOnlyEntity;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.Attribute.PersistentAttributeType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `JpaEntityType.of` 的「缺 `@Id`」护栏（`meta/JpaEntityType.java:55-56`）。
///
/// 为什么用桩元模型而不是真实 Hibernate：Hibernate 7 在**启动期**就拒绝没有标识符的实体
/// （`No identifier specified for entity`），`hbm2ddl` 都到不了，所以真实元模型里永远拿不到
/// 「有属性但没有 `@Id`」的实体类型。这里用一个只回答 `entity(...).getAttributes()` 的桩来驱动
/// 同一段解析逻辑——它钉的是**拒绝文案与异常类型**，不是映射能力，正确性证据仍归真实 Hibernate。
///
/// 第二条用例是同一桩上的正面对照：属性表里带 `isId` 的属性时解析正常返回，因此第一条用例的
/// 异常确实来自「找不到 `@Id`」，而不是桩太瘦导致解析一律失败。
class JpaEntityTypeTest {

    @Test
    @DisplayName("没有 @Id 属性：IllegalArgumentException，消息点名实体类")
    void rejectsEntityWithoutIdAttribute() {
        Metamodel metamodel = metamodelWith(attribute("label", false));

        assertThatThrownBy(() -> JpaEntityType.of(metamodel, LabelOnlyEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("实体 " + LabelOnlyEntity.class.getName() + " 没有 @Id 属性");
    }

    @Test
    @DisplayName("对照：属性表里有 isId 的属性时解析正常，id/version 各自归位")
    void acceptsEntityWithIdAttribute() {
        Metamodel metamodel = metamodelWith(attribute("id", true), attribute("code", false));

        JpaEntityType<DerivedRecord> entityType = JpaEntityType.of(metamodel, DerivedRecord.class);

        assertThat(entityType.type()).isEqualTo(DerivedRecord.class);
        assertThat(entityType.id().name()).isEqualTo("id");
        assertThat(entityType.version()).isNull();
        assertThat(entityType.attributes()).containsOnlyKeys("id", "code");
    }

    private static Metamodel metamodelWith(Attribute<?, ?>... attributes) {
        EntityType<?> entityType = stub(EntityType.class, Map.of("getAttributes", Set.of(attributes)));
        return stub(Metamodel.class, Map.of("entity", entityType));
    }

    private static SingularAttribute<?, ?> attribute(String name, boolean id) {
        // `getPersistentAttributeType` 是写侧判定要用的（关联 vs 标量），桩必须如实回答，
        // 否则解析逻辑一碰它就会以「桩未实现」报错——那是桩太瘦，不是被测代码的问题。
        return stub(
                SingularAttribute.class,
                Map.of(
                        "getName",
                        name,
                        "isId",
                        id,
                        "isVersion",
                        false,
                        "getPersistentAttributeType",
                        PersistentAttributeType.BASIC));
    }

    /// 只回答 `answers` 里列出的方法，其余方法一律显式失败：桩若被调用到未预料的方法，
    /// 用例应当报错而不是静默返回 `null` 让断言失真。`Object` 的三个方法照常实现，
    /// 因为 `Set.of(...)` 这类容器在构造时就会拿元素做去重比较。
    private static <T> T stub(Class<T> type, Map<String, Object> answers) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> type.getSimpleName() + "桩";
            default -> {
                if (answers.containsKey(method.getName())) {
                    yield answers.get(method.getName());
                }
                throw new UnsupportedOperationException("桩未实现: " + type.getSimpleName() + "#" + method.getName());
            }
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
