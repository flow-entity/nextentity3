package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.Shipment;
import io.github.nextentity.jpa.entity.ShipmentAddress;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 查询路径末段即 `@Embedded` **整体对象**时 JPA 后端的登记行为（后端能力差异，见 USAGE §13.4.4）：
/// JPA 把整体嵌入当可嵌入列渲染——投影返回嵌入实例（嵌入列全为 `null` 的行返回 `null`，不产生 join 丢行），
/// `isNull()` / `isNotNull()` 展开为叶子列条件；JDBC 扁平模型只保留嵌入叶子，同一形态显式拒绝
/// （`嵌入对象不能整体投影: <实体全名>.<属性>`，见 `nextentity-jdbc` 的 `EmbeddedPathTest`）。
///
/// 两个后端的路径模型不同（JPA 有嵌入类型、JDBC 只建模扁平叶子）是该差异的根因，已按「登记能力差异、
/// 不收窄 JPA」处理；本用例钉住 JPA 侧现状，防止后续「对齐 JDBC」时把可用能力静默收窄。
class JpaEmbeddedValuePathTest extends JpaTestSupport {

    @Test
    @DisplayName("整体投影 @Embedded：返回嵌入实例，嵌入列全 null 的行返回 null（不产生 join 丢行）")
    void wholeEmbeddedProjectionReturnsEmbeddedInstances() {
        seedShipments();

        List<ShipmentAddress> rows = inTx(entityManager -> shipments(entityManager)
                .query()
                .select(Path.of(Shipment::getAddress))
                .orderBy(Shipment::getId)
                .asc()
                .list());

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getCity()).isEqualTo("Shanghai");
        assertThat(rows.get(1)).isNull();
        assertThat(rows.get(2).getCity()).isEqualTo("Beijing");
    }

    @Test
    @DisplayName("整体嵌入的 isNull 谓词展开为叶子列条件，且 total 与 data 自洽")
    void wholeEmbeddedIsNullExpandsToLeafColumns() {
        seedShipments();

        Slice<Shipment> slice = inTx(entityManager -> shipments(entityManager)
                .query()
                .where(Shipment::getAddress)
                .isNull()
                .orderBy(Shipment::getId)
                .asc()
                .slice(0, 10));

        assertThat(slice.total()).isEqualTo(1L);
        assertThat(slice.data()).extracting(Shipment::getId).containsExactly(2L);
    }

    @Test
    @DisplayName("整体嵌入的 isNotNull 谓词展开为叶子列条件（外键/嵌入列非 null 的行都命中）")
    void wholeEmbeddedIsNotNullExpandsToLeafColumns() {
        seedShipments();

        List<Long> ids =
                inTx(entityManager -> shipments(entityManager)
                        .query()
                        .where(Shipment::getAddress)
                        .isNotNull()
                        .orderBy(Shipment::getId)
                        .asc()
                        .list()
                        .stream()
                        .map(Shipment::getId)
                        .toList());

        assertThat(ids).containsExactly(1L, 3L);
    }

    private void seedShipments() {
        inTx(entityManager -> {
            entityManager.createNativeQuery("delete from shipment").executeUpdate();
            entityManager.persist(new Shipment(1L, new ShipmentAddress("Shanghai")));
            entityManager.persist(new Shipment(2L, null));
            entityManager.persist(new Shipment(3L, new ShipmentAddress("Beijing")));
            return null;
        });
    }

    private EntityOperations<Shipment> shipments(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(Shipment.class);
    }
}
