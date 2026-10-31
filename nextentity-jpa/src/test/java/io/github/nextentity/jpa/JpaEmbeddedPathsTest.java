package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.AmbiguousEmbeddedHolder;
import io.github.nextentity.jpa.entity.Datum;
import io.github.nextentity.jpa.entity.EmbeddedAssociationFlags;
import io.github.nextentity.jpa.entity.EmbeddedFallbackFixtures.EchoDiamondHolder;
import io.github.nextentity.jpa.entity.EmbeddedFallbackFixtures.EchoEmbeddedHolder;
import io.github.nextentity.jpa.entity.EmbeddedFallbackFixtures.ShadowedEmbeddedHolder;
import io.github.nextentity.jpa.entity.EmbeddedFallbackFixtures.TransientGetterHolder;
import io.github.nextentity.jpa.entity.EmbeddedResolutionFixtures.UnannotatedEmbeddedAssociation;
import io.github.nextentity.jpa.entity.GeoCoordinates;
import io.github.nextentity.jpa.entity.Shipment;
import io.github.nextentity.jpa.entity.ShipmentAddress;
import io.github.nextentity.jpa.entity.Warehouse;
import io.github.nextentity.jpa.entity.WarehouseLocation;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `select(Dto.class)` 的嵌平列：DTO 属性名在实体上没有同名属性时，到 `@Embedded` 组件里回找同名叶子
/// （`nextentity-jpa/meta/JpaEmbeddedPaths.java`），再由 `JpaPaths.projectionPath` 渲染成多段 Criteria 路径。
///
/// 这里覆盖四件事：回找本身的几层深度、真实渲染（一层与多层各一条，走投影回找分支）、同名叶子出现
/// 在两个嵌入组件时的显式拒绝（歧义静默挑一个会让结果像数据错），以及回找的边界——关联不是叶子、
/// 直接属性优先、条件路径不回找。
class JpaEmbeddedPathsTest extends JpaTestSupport {

    /// 一层嵌入：`city` → `address.city`。
    record CityDto(String city) {}

    /// 三层嵌入：`zone` → `location.coordinates.zone`。
    record ZoneDto(String zone) {}

    /// 直层 `city` 与嵌入 `city` 并存时，投影该取实体自有列。
    record ShadowedCityDto(String city) {}

    @BeforeEach
    void seedEmbeddedRows() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from Shipment").executeUpdate();
            entityManager.createQuery("delete from Warehouse").executeUpdate();
            entityManager.persist(new Shipment(1L, new ShipmentAddress("Shanghai")));
            entityManager.persist(
                    new Warehouse(1L, new WarehouseLocation("east", new GeoCoordinates("north", new Datum("d-1")))));
            return null;
        });
        drainSql();
    }

    private <T> EntityOperations<T> operations(EntityManager entityManager, Class<T> type) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory()).operations(type);
    }

    @Test
    @DisplayName("回找一层嵌入：Shipment.city → address.city")
    void resolveOneLevelEmbedded() {
        assertThat(meta().embeddedPaths().resolve(Shipment.class, "city")).containsExactly("address", "city");
    }

    @Test
    @DisplayName("回找多层嵌入：Warehouse 的 zone/code/region")
    void resolveNestedEmbeddeds() {
        assertThat(meta().embeddedPaths().resolve(Warehouse.class, "zone"))
                .containsExactly("location", "coordinates", "zone");
        assertThat(meta().embeddedPaths().resolve(Warehouse.class, "code"))
                .containsExactly("location", "coordinates", "datum", "code");
        assertThat(meta().embeddedPaths().resolve(Warehouse.class, "region")).containsExactly("location", "region");
    }

    @Test
    @DisplayName("直接属性与不存在的属性都不回找：返回空清单，交给原路径解析报错")
    void resolveIgnoresDirectAndUnknownAttributes() {
        assertThat(meta().embeddedPaths().resolve(Shipment.class, "id")).isEmpty();
        assertThat(meta().embeddedPaths().resolve(Shipment.class, "nope")).isEmpty();
        assertThat(meta().embeddedPaths().resolve(Warehouse.class, "datum")).isEmpty();
    }

    @Test
    @DisplayName("select(Dto.class) 一层嵌平：city 走 address.city 渲染成列")
    void selectDtoWithOneLevelEmbeddedLeaf() {
        List<CityDto> rows = inTx(entityManager -> operations(entityManager, Shipment.class)
                .query()
                .select(CityDto.class)
                .list());
        assertThat(rows).containsExactly(new CityDto("Shanghai"));
        assertThat(String.join("\n", normalized(drainSql()))).contains("select s1_0.city from shipment s1_0");
    }

    @Test
    @DisplayName("select(Dto.class) 多层嵌平：zone 走 location.coordinates.zone 渲染成列")
    void selectDtoWithNestedEmbeddedLeaf() {
        List<ZoneDto> rows = inTx(entityManager -> operations(entityManager, Warehouse.class)
                .query()
                .select(ZoneDto.class)
                .list());
        assertThat(rows).containsExactly(new ZoneDto("north"));
    }

    @Test
    @DisplayName("同名叶子出现在两个嵌入组件里：显式 IAE，不静默挑一个")
    void ambiguousEmbeddedLeafIsRejected() {
        assertThatThrownBy(() -> meta().embeddedPaths().resolve(AmbiguousEmbeddedHolder.class, "city"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多个 @Embedded")
                .hasMessageContaining("project(ProjectionMapper)");
        assertThatThrownBy(() -> inTx(entityManager -> operations(entityManager, AmbiguousEmbeddedHolder.class)
                        .query()
                        .select(CityDto.class)
                        .list()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多个 @Embedded");
    }

    @Test
    @DisplayName("嵌入里的关联不是叶子：country/owner 不回找成幽灵路径，也不报假歧义")
    void associationsInsideEmbeddedAreNotLeaves() {
        assertThat(meta().embeddedPaths().resolve(EmbeddedAssociationFlags.class, "country"))
                .as("contact.country / billing.country 都是 @ManyToOne，不是可投影列")
                .isEmpty();
        assertThat(meta().embeddedPaths().resolve(UnannotatedEmbeddedAssociation.class, "owner"))
                .as("ref.owner 是 @ManyToOne，回找成 ref.owner 只会拼出取不出值的路径")
                .isEmpty();
    }

    @Test
    @DisplayName("歧义文案只列合法路径：不给候选拼上已消费的叶子名")
    void ambiguousCandidatesAreLegalPaths() {
        assertThatThrownBy(() -> meta().embeddedPaths().resolve(EchoEmbeddedHolder.class, "code"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[contact, code]")
                .hasMessageContaining("[contact, inner, code]")
                .hasMessageNotContaining("[contact, code, inner, code]");
    }

    @Test
    @DisplayName("菱形复用仍报歧义：同一可嵌入类型经两条分支再次出现时两个候选都要收集")
    void diamondEmbeddedReuseStillReportsAmbiguity() {
        assertThatThrownBy(() -> meta().embeddedPaths().resolve(EchoDiamondHolder.class, "code"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[left, inner, code]")
                .hasMessageContaining("[right, inner, code]");
    }

    @Test
    @DisplayName("条件路径不回找：非持久化 getter 同名时显式 IAE，不静默按嵌入列过滤")
    void conditionPathOnTransientGetterIsNotRewritten() {
        inTx(entityManager -> {
            entityManager.persist(new TransientGetterHolder(1L, new ShipmentAddress("Shanghai")));
            return null;
        });
        assertThatThrownBy(() -> inTx(entityManager -> operations(entityManager, TransientGetterHolder.class)
                        .query()
                        .where(TransientGetterHolder::getCity)
                        .eq("Shanghai")
                        .list()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("直接属性优先：实体自有 city 与嵌入 city 并存时投影选实体列")
    void directAttributeWinsOverEmbeddedLeaf() {
        inTx(entityManager -> {
            entityManager.persist(new ShadowedEmbeddedHolder(1L, "direct", new ShipmentAddress("embedded")));
            return null;
        });
        drainSql();
        List<ShadowedCityDto> rows = inTx(entityManager -> operations(entityManager, ShadowedEmbeddedHolder.class)
                .query()
                .select(ShadowedCityDto.class)
                .list());
        assertThat(rows).containsExactly(new ShadowedCityDto("direct"));
        assertThat(String.join("\n", normalized(drainSql()))).doesNotContain("address_city");
    }
}
