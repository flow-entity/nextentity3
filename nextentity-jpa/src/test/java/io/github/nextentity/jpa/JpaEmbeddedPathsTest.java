package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.AmbiguousEmbeddedHolder;
import io.github.nextentity.jpa.entity.Datum;
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
/// （`nextentity-jpa/meta/JpaEmbeddedPaths.java`），再由 `JpaPaths.path` 渲染成多段 Criteria 路径。
///
/// 这里覆盖三件事：回找本身的几层深度、真实渲染（一层与多层各一条，走 `JpaPaths` 的末段回找分支）、
/// 以及同名叶子出现在两个嵌入组件时的显式拒绝（歧义静默挑一个会让结果像数据错）。
class JpaEmbeddedPathsTest extends JpaTestSupport {

    /// 一层嵌入：`city` → `address.city`。
    record CityDto(String city) {}

    /// 三层嵌入：`zone` → `location.coordinates.zone`。
    record ZoneDto(String zone) {}

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
}
