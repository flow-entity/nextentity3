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

    /// `containsIfNotEmpty("")` 跳过条件：等于「无条件」，命中**全表**。
    ///
    /// 钉具体 id 集合而不是 `hasSize(4)`：只数个数时，「4 行里混进重复行、丢掉一行」
    /// （如 `[1,1,2,3]`）与「投影取错列但恰好也是 4 行」都能通过；精确集合同时钉住
    /// 「命中哪些行」与「没有重复」。
    @Test
    void containsIfNotEmptyWithEmptyStringSkipsCondition() {
        List<T> rows = query().where(nameRef()).containsIfNotEmpty("").list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
    }

    /// `containsIfNotEmpty(值)` 生效。
    @Test
    void containsIfNotEmptyWithValueApplies() {
        List<T> rows = query().where(nameRef()).containsIfNotEmpty("pie").list();

        assertThat(rows).extracting(Item::id).containsExactly(APPLE_PIE_ID);
    }

    /// `likeIfNotNull(null)` 跳过条件（不传 `null` 给数据库）：等于「无条件」，命中**全表**。
    ///
    /// 同样钉具体 id 集合：只断 `hasSize(4)` 时，重复行 / 丢行 / 取错列恰好 4 行都能混过去。
    @Test
    void likeIfNotNullWithNullSkipsCondition() {
        List<T> rows = query().where(nameRef()).likeIfNotNull(null).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
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

    /// `contains` **不转义**值里的通配符：`%` 被数据库当作「任意串」，不是字面百分号。
    ///
    /// 本库把 `contains(v)` 拼成 `%v%` 直接下发（`Specs.contains`），值里的 `%` / `_` / `\`
    /// 一个都不转义。于是 `contains("a%")` 实际是 `%a%%`（等价 `%a%`），命中「名称含 a」的三行；
    /// 若语义是「含字面子串 `a%`」，答案应是空集。`contains("%")` 更是退化成 `%`，命中全表。
    ///
    /// 钉住当前行为，是为了让两种「修 bug」的方式都会变红：给值加转义（`%a\%%` → 空集），
    /// 或把拼接改成 `v + "%"`（前缀匹配 → 只剩 apple 一族）。
    @Test
    void containsDoesNotEscapeWildcards() {
        List<T> percent = query().where(nameRef()).contains("a%").list();

        assertThat(percent).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID, APPLE_PIE_ID);

        List<T> barePercent = query().where(nameRef()).contains("%").list();

        assertThat(barePercent)
                .extracting(Item::id)
                .containsExactlyInAnyOrder(APPLE_ID, BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
    }

    /// `_` 同样是通配符：`contains("p_l")` → `%p_l%`，`_` 匹配任意单字符，命中 `apple`（`ppl`）
    /// 与 `apple pie`；字面子串语义下（夹具没有名字含 `p_l`）应为空集。
    @Test
    void containsTreatsUnderscoreAsSingleCharWildcard() {
        List<T> rows = query().where(nameRef()).contains("p_l").list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, APPLE_PIE_ID);
    }

    /// 反斜杠仍是 LIKE 的转义符：`contains("p\\l")` 下发 `%p\\l%`，`\\l` 是字面 `l`，
    /// 模式等于「含 `pl`」，夹具里没有 → 空集。
    ///
    /// 与上一条合起来钉住「本库既不给值加转义，也不关闭数据库的默认转义符」：
    /// 只改一处（加转义、或把反斜杠当普通字符）都会让两条用例之一变红。
    @Test
    void containsLeavesBackslashAsLikeEscape() {
        List<T> rows = query().where(nameRef()).contains("p\\l").list();

        assertThat(rows).isEmpty();
    }

    /// `like` 的模式原样进查询、通配符由调用方掌握（与 `contains` 相反，不替你包 `%`）：
    /// `like("p_l")` 是「整名恰好 3 个字符、首 p 尾 l」——夹具里没有；包上 `%` 之后才命中 `apple` 一族。
    @Test
    void likePatternWildcardsAreInterpretedByDatabase() {
        assertThat(query().where(nameRef()).like("p_l").list()).isEmpty();

        assertThat(query().where(nameRef()).like("%p_l%").list())
                .extracting(Item::id)
                .containsExactlyInAnyOrder(APPLE_ID, APPLE_PIE_ID);
    }
}
