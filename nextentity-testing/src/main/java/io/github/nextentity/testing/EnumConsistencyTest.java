package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/// 枚举列的一致性：按枚举值比较、按枚举存储形态排序。
///
/// 夹具状态分布：`NEW` → id 1、4；`ACTIVE` → id 2；`DONE` → id 3。
/// 排序断言只在 `@Enumerated(STRING)` 下成立（字典序 `ACTIVE < DONE < NEW`），
/// 与 point-mall 的 50/51 处映射一致；若某后端改用序号存储，排序断言会立刻失败——这是有意的暴露。
///
/// @param <T> 后端实体类型
public abstract class EnumConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 枚举 `eq`：按枚举值比较，与存储形态无关。
    @Test
    void enumEqMatchesRowsWithThatStatus() {
        List<T> rows = query().where(statusRef()).eq(ItemStatus.NEW).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, APPLE_PIE_ID);
    }

    /// 枚举 `orderBy`：同值之间相对顺序未定义，只断言按枚举存储顺序排列。
    @Test
    void orderByEnumIsAscending() {
        List<T> rows = query().orderBy(statusRef()).asc().list();

        assertThat(rows)
                .extracting(Item::status)
                .containsExactly(ItemStatus.ACTIVE, ItemStatus.DONE, ItemStatus.NEW, ItemStatus.NEW);
    }
}
