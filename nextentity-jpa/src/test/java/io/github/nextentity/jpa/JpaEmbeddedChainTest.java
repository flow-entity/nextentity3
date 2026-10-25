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
import io.github.nextentity.jpa.entity.Datum;
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
    /// 第三行 `location = null`：给下面那条「嵌入为 null 的行也得返回」的用例留素材。
    @BeforeEach
    void seedWarehouses() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from Warehouse").executeUpdate();
            entityManager.persist(
                    new Warehouse(1L, new WarehouseLocation("east", new GeoCoordinates("north", new Datum("d-1")))));
            entityManager.persist(new Warehouse(2L, new WarehouseLocation("east", new GeoCoordinates("south"))));
            entityManager.persist(new Warehouse(3L, null));
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
                .containsExactly("select w1_0.id,w1_0.datum_code,w1_0.zone,w1_0.region from warehouse w1_0"
                        + " where w1_0.zone=? order by w1_0.id");
    }

    /// 嵌入属性为 `null` 的行必须照样被查出来。
    ///
    /// 这条钉的是「嵌套路径一律 join」那条等价判定的**边界**：Hibernate 7 把嵌入的 `join` 摊平成
    /// 同一张表的列，所以现在看不出差异；但一旦某个实现给嵌入 `join` 加上 `is not null` 谓词
    /// （INNER 的常见实现），嵌入列全 null 的行就会静默消失——那是结果差异，不是格式差异。
    /// 换 Hibernate 大版本或接第二后端时，这条会先红。
    ///
    /// **排序键必须走嵌入路径**：只按 `id` 排序时整条语句不碰 `location`，
    /// `JpaPaths.navigate` 根本不会被调用，这条用例就成了空转（第一版就是这样，
    /// 注入「一律 join」照样绿）。按 `location.coordinates.zone` 排序后，语句里必须出现
    /// `w1_0.zone`，嵌入路径真的被走过；此时任何"给嵌入 join 加 is not null"的实现
    /// 都会让 3 号行消失，断言当场红。null 的排序位置按方言可能不同，所以断言不依赖顺序。
    @Test
    @DisplayName("嵌入属性为 null 的行照样返回：嵌入 join 若加 is not null 就会漏行")
    void nullEmbeddedRowIsReturned() {
        QuerySpec spec = new QuerySpec(
                Warehouse.class,
                new EntitySelection(),
                null,
                List.of(),
                null,
                List.of(new Ordering(
                        AttributePath.of(Warehouse.class, "location", "coordinates", "zone"), Ordering.Direction.ASC)),
                Window.NONE,
                null);

        List<Long> ids = inTx(entityManager ->
                executor(entityManager).<Warehouse>list(compiler(entityManager).compile(spec)).stream()
                        .map(Warehouse::getId)
                        .toList());

        // 语义断言在前：排查时"3 号行消失了"比"语句多了三个词"直接得多。
        assertThat(ids).as("嵌入为 null 的 3 号行不能被过滤掉").containsExactlyInAnyOrder(1L, 2L, 3L);
        // 语句断言在后，它兜的是另一侧：某个实现若不改语句文本、只在结果转换层丢掉 null 嵌入行，
        // 上面那条能抓；若它给嵌入 join 加了 is not null，语句这里先红。两条是"语义 + 机制"，缺一有盲区。
        assertThat(normalized(drainSql()))
                .as("排序键必须落在仓库自己的嵌入列上，否则这条用例不经过嵌入路径")
                .containsExactly("select w1_0.id,w1_0.datum_code,w1_0.zone,w1_0.region from warehouse w1_0"
                        + " order by w1_0.zone");
    }

    /// 四段路径（三层嵌入）：`location.coordinates.datum.code`。
    ///
    /// 递归是同一段代码，但第四层是**前缀缓存**第一次需要新建前缀项的地方——前三段早被
    /// 前序路径填满，所以三层路径测不到缓存的行为。只有 1 号行有 `datum`，2 号行的
    /// `coordinates.datum` 为 `null`：命中一行才说明最内层真的渲染成仓库自己的列。
    @Test
    @DisplayName("三层嵌入的四段路径：条件落在最内层列上，投影/条件/排序都走仓库自己的列")
    void nestedEmbeddedPathAtFourthSegmentUsesGet() {
        AttributePath code = AttributePath.of(Warehouse.class, "location", "coordinates", "datum", "code");
        QuerySpec spec = new QuerySpec(
                Warehouse.class,
                new EntitySelection(),
                new Comparison(code, ComparisonOperator.EQ, Literal.of("d-1")),
                List.of(),
                null,
                List.of(new Ordering(AttributePath.of(Warehouse.class, "id"), Ordering.Direction.ASC)),
                Window.NONE,
                null);

        List<Long> ids = inTx(entityManager ->
                executor(entityManager).<Warehouse>list(compiler(entityManager).compile(spec)).stream()
                        .map(Warehouse::getId)
                        .toList());

        assertThat(ids).as("只有 1 号行的最内层是 d-1").containsExactly(1L);
        assertThat(normalized(drainSql()))
                .as("四段路径一路 get：条件落在 warehouse 自己的 datum_code 列上")
                .containsExactly("select w1_0.id,w1_0.datum_code,w1_0.zone,w1_0.region from warehouse w1_0"
                        + " where w1_0.datum_code=? order by w1_0.id");
    }

    private JpaCompiler compiler(EntityManager entityManager) {
        return new JpaCompiler(entityManager, meta());
    }

    private JpaExecutor executor(EntityManager entityManager) {
        return new JpaExecutor(entityManager, meta());
    }
}
