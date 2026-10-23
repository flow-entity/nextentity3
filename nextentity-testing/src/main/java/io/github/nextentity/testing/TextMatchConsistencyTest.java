package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/// 字符串算子的一致性：`contains` 家族的包含语义与空值/空串短路。
///
/// @param <T> 后端实体类型
public abstract class TextMatchConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// `contains` 是「任意位置包含」，不是前缀匹配。
    @Test
    void containsMatchesAnyPosition() {
        List<T> rows = query().where(nameRef()).contains("app").list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, APPLE_PIE_ID);
    }

    /// `contains` 不命中子串以外的东西。
    @Test
    void containsDoesNotMatchUnrelatedText() {
        List<T> rows = query().where(nameRef()).contains(MISSING_NAME).list();

        assertThat(rows).isEmpty();
    }

    /// `containsIfNotEmpty("")` 跳过条件。
    @Test
    void containsIfNotEmptyWithEmptyStringSkipsCondition() {
        List<T> rows = query().where(nameRef()).containsIfNotEmpty("").list();

        assertThat(rows).hasSize(4);
    }

    /// `containsIfNotEmpty(值)` 生效。
    @Test
    void containsIfNotEmptyWithValueApplies() {
        List<T> rows = query().where(nameRef()).containsIfNotEmpty("pie").list();

        assertThat(rows).extracting(Item::id).containsExactly(APPLE_PIE_ID);
    }

    /// `likeIfNotNull(null)` 跳过条件（不传 `null` 给数据库）。
    @Test
    void likeIfNotNullWithNullSkipsCondition() {
        List<T> rows = query().where(nameRef()).likeIfNotNull(null).list();

        assertThat(rows).hasSize(4);
    }

    /// 纯 `like`：模式原样进 SQL，api **不**替调用方包 `%`。
    @Test
    void likeUsesPatternAsIs() {
        List<T> rows = query().where(nameRef()).like("%pie").list();

        assertThat(rows).extracting(Item::id).containsExactly(APPLE_PIE_ID);
    }

    /// 纯 `like` 不带通配符时是精确匹配，与 `contains` 的 `%v%` 语义不同。
    @Test
    void likeWithoutWildcardMatchesExactly() {
        List<T> rows = query().where(nameRef()).like(APPLE_NAME).list();

        assertThat(rows).extracting(Item::id).containsExactly(APPLE_ID);
    }
}
