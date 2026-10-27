package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.spec.QuerySpec;
import java.util.List;
import java.util.Objects;

/// 编译产物：查询规格 + 渲染好的 SQL。
///
/// 带 `QuerySpec` 是因为 core 的 `count()/exists()` 只清排序与窗口，规格本身无法区分
/// 「这是计数」；执行器需要规格才能回调 `JdbcCompiler#count(QuerySpec)`。
///
/// @param spec      原始查询规格
/// @param statement 渲染结果
public record JdbcQuery(QuerySpec spec, SqlStatement statement) {

    /// 紧凑构造器：两字段必填。
    public JdbcQuery {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(statement, "statement");
    }

    /// @return SQL 文本
    public String sql() {
        return statement.sql();
    }

    /// @return 绑定值
    public List<Object> parameters() {
        return statement.parameters();
    }

    /// @return 结果列静态类型
    public List<Class<?>> columnTypes() {
        return statement.columnTypes();
    }
}
