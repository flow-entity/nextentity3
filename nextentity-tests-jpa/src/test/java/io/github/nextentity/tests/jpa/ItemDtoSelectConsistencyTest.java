package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.DtoSelectConsistencyTest;
import io.github.nextentity.testing.FixtureRow;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 把 `select(Class)` DTO 投影的一致性用例接到 JPA 后端上。
///
/// 实体目标类型那两条只能放在后端模块：需要 `ItemEntity.class` 字面量，而 `ConsistencySupport`
/// 只暴露查询入口、不暴露实体类型。
class ItemDtoSelectConsistencyTest extends DtoSelectConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_dto_select");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 目标类型 = 实体本身：退化为整实体查询（`select(ItemEntity.class)` 是合法写法）。
    @Test
    @DisplayName("select(实体类)：退化为整实体查询，不是 DTO 反射")
    void entityTargetDegradesToEntityQuery() {
        List<ItemEntity> rows =
                query().select(ItemEntity.class).orderBy(idRef()).asc().list();

        assertThat(rows).hasSize(fixtureRows().size());
        assertThat(rows)
                .extracting(ItemEntity::name)
                .containsExactlyElementsOf(
                        fixtureRows().stream().map(FixtureRow::name).toList());
    }

    /// 整实体目标 + `distinct()`：列集不是显式列表，`SELECT DISTINCT` 表达不出来 → 显式拒绝。
    @Test
    @DisplayName("select(实体类).distinct()：显式拒绝（UOE），不静默忽略标志")
    void entityTargetRejectsDistinct() {
        assertThatThrownBy(() -> query().distinct().select(ItemEntity.class))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
