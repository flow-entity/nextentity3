package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.entity.GeoCoordinates;
import io.github.nextentity.jpa.entity.Warehouse;
import io.github.nextentity.jpa.entity.WarehouseLocation;
import io.github.nextentity.jpa.exec.JpaExecutor;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 两段嵌入（嵌入里再嵌一层）的三段路径：`location.coordinates.zone`。
///
/// 钉住的形状：三段路径的**投影、条件、排序**全部落在仓库自己的列上（`w1_0.zone`），
/// 一条 join 都不出现。此前 `JpaPathsNestedTest` 只覆盖两段（`Shipment#address.city`）。
///
/// 关于 `compile/JpaPaths.java:62` 的关联判定：**它不是可观测的**，这里如实记下实测。
/// 把判定换成恒真（中段一律 `join`）后，两段与三段嵌入用例**都仍然绿**——
/// Hibernate 7 的嵌入属性 `join` 是"虚拟"的，渲染成同一批列，语句与结果逐字节不变。
/// 所以那是一条等价变异（`docs/DESIGN.md` §5 等价变异清单），不是缺口；
/// 本类保留的价值是钉住三段嵌入这条路径形状本身。
class JpaEmbeddedChainTest extends JpaTestSupport {

    /// 两行只有 `zone` 不同：命中一行才说明第三段真的渲染成仓库自己的列。
    @BeforeEach
    void seedWarehouses() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from Warehouse").executeUpdate();
            entityManager.persist(new Warehouse(1L, new WarehouseLocation("east", new GeoCoordinates("north"))));
            entityManager.persist(new Warehouse(2L, new WarehouseLocation("east", new GeoCoordinates("south"))));
            return null;
        });
        drainSql();
    }

    @AfterEach
    void clearWarehouses() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from Warehouse").executeUpdate();
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("两段嵌入的三段路径：一路 get，语句里不出现 join")
    void nestedEmbeddedPathUsesGet() {
        assertThat(meta().association(Warehouse.class, "location")).isFalse();
        assertThat(meta().association(WarehouseLocation.class, "coordinates")).isFalse();

        AttributePath zone = AttributePath.of(Warehouse.class, "location", "coordinates", "zone");
        QuerySpec spec = new QuerySpec(
                Warehouse.class,
                new EntitySelection(),
                new Comparison(zone, ComparisonOperator.EQ, Literal.of("north")),
                List.of(),
                null,
                List.of(new Ordering(AttributePath.of(Warehouse.class, "id"), Ordering.Direction.ASC)),
                Window.NONE,
                null);

        List<Long> ids = inTx(entityManager ->
                executor(entityManager).<Warehouse>list(compiler(entityManager).compile(spec)).stream()
                        .map(Warehouse::getId)
                        .toList());

        assertThat(ids).containsExactly(1L);
        assertThat(normalized(drainSql()))
                .containsExactly("select w1_0.id,w1_0.zone,w1_0.region from warehouse w1_0 where w1_0.zone=?"
                        + " order by w1_0.id");
    }

    private JpaCompiler compiler(EntityManager entityManager) {
        return new JpaCompiler(entityManager, meta());
    }

    private JpaExecutor executor(EntityManager entityManager) {
        return new JpaExecutor(entityManager, meta());
    }
}
