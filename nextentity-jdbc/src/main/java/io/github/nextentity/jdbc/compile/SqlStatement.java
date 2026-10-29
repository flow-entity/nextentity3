package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.util.ImmutableList;
import java.util.List;
import java.util.Objects;

/// 一条已渲染的 SQL：文本、按渲染顺序排列的绑定值、结果列静态类型。
///
/// @param sql         SQL 文本
/// @param parameters  绑定值，顺序与 SQL 里的 `?` 一致
/// @param columnTypes 结果列静态类型，读法见设计 §3.0
public record SqlStatement(String sql, List<Object> parameters, List<Class<?>> columnTypes) {

    /// 紧凑构造器：校验非空并复制为不可变列表。
    ///
    /// **只在构造器拷一次**：渲染方（`SqlRenderer` / `WriteSql`）交出的列表从此不再被触碰，
    /// 所以它们直接传原实例，不预先包一层。
    ///
    /// 用 `ImmutableList.copyOf` 而不是 `List.copyOf`：`IN` 列表允许 `null` 元素，`List.copyOf` 拒绝 `null`。
    public SqlStatement {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(columnTypes, "columnTypes");
        parameters = ImmutableList.copyOf(parameters);
        columnTypes = ImmutableList.copyOf(columnTypes);
    }
}
