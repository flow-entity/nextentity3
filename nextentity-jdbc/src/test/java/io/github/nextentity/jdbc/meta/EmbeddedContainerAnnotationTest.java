package io.github.nextentity.jdbc.meta;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `@Embedded` 容器字段上的 `@Id` / `@Version` / `@GeneratedValue` 都必须元数据期拒绝。
///
/// 容器属性在 `JdbcEntityAttribute` 里 `column() == null`，`isVersion()` / `isId()` / `isGenerated()` 恒为 `false`，
/// 不拒绝就等于静默吞注解。`@Id @Embedded` 在 Hibernate 里是合法的复合主键，本层不支持复合主键 → 按
/// `COMPOSITE_ID` 拒绝（只有这一个主键时原来会误报「实体必须有且只有一个 @Id 字段」，与普通 @Id 并存时
/// 则被静默忽略，两种形态必须得到同一个结论）；`@Version` / `@GeneratedValue` 标在容器上在 Hibernate 侧是
/// `EntityManagerFactory` 构建期错误。最危险的是 `@Version`：实体声明了版本列，实际没有乐观锁。
/// 拒绝时机在 `JdbcEmbedded#embedded` 入口（与容器 `@Convert`、嵌入内部同族注解同处），路径带容器字段名。
class EmbeddedContainerAnnotationTest {

    @Test
    @DisplayName("@Version 写在 @Embedded 容器字段上：元数据期拒绝（否则实体声明了版本列却没有乐观锁）")
    void versionOnEmbeddedContainerIsRejected() {
        assertThatThrownBy(() -> new JdbcMeta().entityType(VersionOnEmbedded.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("@Embedded 字段上的 @Version 未支持: " + VersionOnEmbedded.class.getName() + ".amount");
    }

    @Test
    @DisplayName("唯一的 @Id 落在 @Embedded 容器上：按复合主键拒绝，而不是误报缺 @Id")
    void idOnEmbeddedContainerIsRejectedAsCompositeId() {
        assertThatThrownBy(() -> new JdbcMeta().entityType(IdOnEmbedded.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("复合主键未支持: " + IdOnEmbedded.class.getName() + ".amount");
    }

    @Test
    @DisplayName("普通 @Id 与容器上的 @Id 并存：同样按复合主键拒绝，而不是静默忽略")
    void containerIdAlongsideScalarIdIsRejectedAsCompositeId() {
        assertThatThrownBy(() -> new JdbcMeta().entityType(ScalarAndContainerId.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("复合主键未支持: " + ScalarAndContainerId.class.getName() + ".amount");
    }

    @Test
    @DisplayName("@GeneratedValue 写在 @Embedded 容器字段上同样要拒绝")
    void generatedValueOnEmbeddedContainerIsRejected() {
        assertThatThrownBy(() -> new JdbcMeta().entityType(GeneratedValueOnEmbedded.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(
                        "@Embedded 字段上的 @GeneratedValue 未支持: " + GeneratedValueOnEmbedded.class.getName() + ".amount");
    }

    @Test
    @DisplayName("对照：嵌入类型内部的 @Version 已被拒绝（容器与叶子必须同口径）")
    void versionInsideEmbeddableIsRejected() {
        assertThatThrownBy(() -> new JdbcMeta().entityType(VersionInsideEmbeddable.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("嵌入属性内不支持 @Version");
    }

    @Entity
    @Table(name = "id_on_embedded")
    static class IdOnEmbedded {

        @Id
        @Embedded
        Money amount;

        protected IdOnEmbedded() {}
    }

    @Entity
    @Table(name = "scalar_and_container_id")
    static class ScalarAndContainerId {

        @Id
        Long id;

        @Id
        @Embedded
        Money amount;

        protected ScalarAndContainerId() {}
    }

    @Entity
    @Table(name = "generated_value_on_embedded")
    static class GeneratedValueOnEmbedded {

        @Id
        Long id;

        @GeneratedValue
        @Embedded
        Money amount;

        protected GeneratedValueOnEmbedded() {}
    }

    @Entity
    @Table(name = "version_on_embedded")
    static class VersionOnEmbedded {

        @Id
        Long id;

        @Version
        @Embedded
        Money amount;

        protected VersionOnEmbedded() {}
    }

    @Entity
    @Table(name = "version_inside_embedded")
    static class VersionInsideEmbeddable {

        @Id
        Long id;

        @Embedded
        VersionedMoney amount;

        protected VersionInsideEmbeddable() {}
    }

    @Embeddable
    static class Money {

        int cents;

        protected Money() {}
    }

    @Embeddable
    static class VersionedMoney {

        int cents;

        @Version
        int version;

        protected VersionedMoney() {}
    }
}
