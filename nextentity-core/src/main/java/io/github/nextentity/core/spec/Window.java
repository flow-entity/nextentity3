package io.github.nextentity.core.spec;

/// 分页窗口：`OFFSET` / `LIMIT`，两者都可缺省。
///
/// 缺省用 `null` 而不用 `Optional`：这是值对象，调用方要么给了数要么没给，
/// 用 `null` 表达"没给"最直接；`Window.NONE` 就是两个都没给。
///
/// @param offset 偏移量，可为 null
/// @param limit  最大行数，可为 null
public record Window(Integer offset, Integer limit) {

    /// 无窗口：既不分页也不限行。
    public static final Window NONE = new Window(null, null);

    /// 紧凑构造器：负数在构造期即拒绝。
    public Window {
        if (offset != null && offset < 0) {
            throw new IllegalArgumentException("offset 不能为负数: " + offset);
        }
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("limit 不能为负数: " + limit);
        }
    }

    /// 是否限制了行数（决定 2.2.3 的"分页自动加主键排序"是否生效）。
    ///
    /// @return `limit` 非 `null` 即受限：`limit = 0` 也算受限（会补主键排序，但不截断行数）
    public boolean limited() {
        return limit != null;
    }
}
