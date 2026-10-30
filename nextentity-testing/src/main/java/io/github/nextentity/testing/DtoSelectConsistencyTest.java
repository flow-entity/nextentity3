package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `SelectStep#select(Class)` 按 DTO 类型反射投影的一致性：属性名与实体属性同名时，列集按 DTO 声明顺序
/// 取出、行装回 DTO；`distinct()` 修饰同一列集。
///
/// 期望值一律读 {@link FixtureRow}（内存 oracle），不是抄一遍实体查询的结果——否则两边一起错也算绿。
///
/// 目标类型恰好是实体本身时的「退化成整实体查询」与 `distinct()` 拒绝放在各后端模块里测：
/// 那个分支需要实体 `Class` 字面量，{@link ConsistencySupport} 只暴露查询入口、不暴露实体类型。
///
/// @param <T> 后端实体类型
public abstract class DtoSelectConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// record DTO：组件名与实体属性同名时按名映射；两列足够证明列序与类型。
    ///
    /// @param id   主键
    /// @param name 名称
    public record ItemIdName(long id, String name) {}

    /// 单枚举列的 DTO：证明 `@Enumerated(STRING)` 的枚举在 DTO 投影里同样按类型装回。
    ///
    /// @param status 状态
    public record ItemStatusOnly(ItemStatus status) {}

    /// 普通类 DTO（setter 形态）：钉的是「有 `setXxx` 的类同样能当目标类型」。
    public static final class ItemNameStatusBean {

        private String name;

        private ItemStatus status;

        /// 无参构造器：普通类 DTO 的装配入口。
        public ItemNameStatusBean() {}

        /// @return 名称
        public String getName() {
            return name;
        }

        /// @param name 名称
        public void setName(String name) {
            this.name = name;
        }

        /// @return 状态
        public ItemStatus getStatus() {
            return status;
        }

        /// @param status 状态
        public void setStatus(ItemStatus status) {
            this.status = status;
        }
    }

    /// record 形态：DTO 的每个组件一列，行装回 record；与内存 oracle 逐行相等。
    @Test
    @DisplayName("select(Dto.class)：record DTO 按属性名取列并逐行装回")
    void recordDtoMatchesFixtureRows() {
        List<ItemIdName> rows =
                query().select(ItemIdName.class).orderBy(idRef()).asc().list();

        assertThat(rows)
                .containsExactlyElementsOf(fixtureRows().stream()
                        .map(row -> new ItemIdName(row.id(), row.name()))
                        .toList());
    }

    /// 普通类形态：`setXxx` 定位属性名、列序按属性名排序，装回实例后与 oracle 相等。
    @Test
    @DisplayName("select(Dto.class)：普通类 DTO 走 setter 装配，值与 record 形态一致")
    void beanDtoMatchesFixtureRows() {
        List<ItemNameStatusBean> rows =
                query().select(ItemNameStatusBean.class).orderBy(idRef()).asc().list();

        assertThat(rows)
                .extracting(ItemNameStatusBean::getName, ItemNameStatusBean::getStatus)
                .containsExactlyElementsOf(fixtureRows().stream()
                        .map(row -> org.assertj.core.groups.Tuple.tuple(row.name(), row.status()))
                        .toList());
    }

    /// `distinct()` 修饰 DTO 投影：列集不变，只去重。
    ///
    /// 不钉顺序：`distinct()` 投影不自动按主键排序（见 `Collector` 类注释），只钉集合相等。
    @Test
    @DisplayName("distinct().select(Dto.class)：列集不变、结果去重")
    void distinctDtoSelectDeduplicates() {
        List<ItemStatusOnly> rows =
                query().distinct().select(ItemStatusOnly.class).list();

        assertThat(rows).extracting(ItemStatusOnly::status).doesNotHaveDuplicates();
        assertThat(rows)
                .extracting(ItemStatusOnly::status)
                .containsExactlyInAnyOrderElementsOf(fixtureRows().stream()
                        .map(FixtureRow::status)
                        .distinct()
                        .toList());
    }

    /// 接口目标类型：接口投影需要运行期代理，本轮不提供 → IAE（消息点明改用手写映射器）。
    @Test
    @DisplayName("select(接口)：显式拒绝（IAE），并指向 project(ProjectionMapper)")
    void interfaceTargetIsRejected() {
        assertThatThrownBy(() -> query().select(ItemView.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project(ProjectionMapper)");
    }

    /// 空表：DTO 投影返回空列（不是一行 null）。
    @Test
    @DisplayName("空表：DTO 投影返回空列表")
    void emptyTableYieldsEmptyDtoList() {
        support().seedData(List.of());

        assertThat(query().select(ItemIdName.class).list()).isEmpty();
    }

    /// 接口投影目标（只为触发拒绝分支）。
    interface ItemView {

        /// @return 名称
        String name();
    }
}
