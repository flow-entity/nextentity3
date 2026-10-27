package io.github.nextentity.jdbc.compile;

import java.util.ArrayList;
import java.util.Collections;
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
    /// 不用 `List.copyOf`：`IN` 列表允许 `null` 元素，而 `List.copyOf` 拒绝 `null`。
    public SqlStatement {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(columnTypes, "columnTypes");
        parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
        columnTypes = Collections.unmodifiableList(new ArrayList<>(columnTypes));
    }
}
