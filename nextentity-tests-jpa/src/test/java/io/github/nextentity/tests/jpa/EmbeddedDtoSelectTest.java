package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// `select(Dto.class)` 的嵌平列（H2 通道）：DTO 属性名在实体上找不到同名属性时，按 `@Embedded` 组件
/// 回找同名叶子——`city` → `address.city`、`zone` → `address.geo.zone`（两层嵌入）。
///
/// 2.2.3 用 `@EntityPath("address.street")` 显式指路，3.x 没有注解面，按名回找是唯一可表达的形态；
/// 这里钉住三件事：解析出来的列真的取到值、整句 SQL 的列清单（含嵌入摊平后的列名）、
/// 同名叶子出现在两个嵌入组件里时显式拒绝。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddedDtoSelectTest {

    private final WriteRoundTripSupport<EmbeddedDtoSelectEntity> support =
            WriteRoundTripSupport.onH2("embedded_dto_select", EmbeddedDtoSelectEntity.class, ContactItemEntity.class);

    @AfterAll
    void closeSupport() {
        support.close();
    }

    /// 扁平 DTO（record）的 `city` / `zone` 回找到一层与两层嵌入叶子，值逐行装回；null 嵌入只让叶子为 null。
    @Test
    void dtoSelectResolvesEmbeddedLeaves() {
        support.inTransaction(_ -> support.operations()
                .insertAll(List.of(
                        new EmbeddedDtoSelectEntity(1L, "alpha", new PostalAddress("Springfield", "z1")),
                        new EmbeddedDtoSelectEntity(2L, "beta", null))));
        support.clearSql();

        List<EmbeddedAddress> rows = support.operations()
                .query()
                .select(EmbeddedAddress.class)
                .orderBy(Path.of(EmbeddedDtoSelectEntity::id))
                .asc()
                .list();

        assertThat(rows)
                .containsExactly(
                        new EmbeddedAddress(1L, "alpha", "Springfield", "z1"),
                        new EmbeddedAddress(2L, "beta", null, null));
        // 嵌入叶子的列名（`city` / `zone`，由 `@Column` 决定）不是本框架拼的：整句断言钉的是
        // 「DTO 的扁平属性名确实被解析成了这两列」，列名本身变化不是本能力的回归信号。
        assertThat(support.drainSql()).singleElement().satisfies(sql -> assertThat(
                        WriteRoundTripSupport.normalized(sql))
                .startsWith("select edse1_0.id,edse1_0.name,edse1_0.city,edse1_0.zone from"
                        + " embedded_dto_select edse1_0"));
    }

    /// 同名叶子（`city`）出现在 `home` / `office` 两个嵌入组件里：无法唯一确定列，显式拒绝。
    ///
    /// 不静默挑一个是刻意的——挑错那次返回别的列，结果看着像数据错，而不是配置问题。
    @Test
    void ambiguousEmbeddedLeafIsRejected() {
        assertThatThrownBy(() -> support.operationsFor(ContactItemEntity.class)
                        .query()
                        .select(CityOnly.class)
                        .list())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多个 @Embedded")
                .hasMessageContaining("project(ProjectionMapper)");
    }

    /// 嵌平 DTO：`city` 是一层叶子、`zone` 是两层叶子。
    ///
    /// @param id   主键
    /// @param name 名称
    /// @param city `address.city`
    /// @param zone `address.geo.zone`
    public record EmbeddedAddress(Long id, String name, String city, String zone) {}

    /// 只取嵌入叶子的 DTO：用来触发「两个 `@Embedded` 里同名叶子」的歧义分支。
    ///
    /// @param city `home.city` 还是 `office.city`？无法确定
    public record CityOnly(String city) {}
}
