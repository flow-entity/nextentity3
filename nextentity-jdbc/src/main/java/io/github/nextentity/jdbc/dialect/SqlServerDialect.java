package io.github.nextentity.jdbc.dialect;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.core.spec.Window;
import java.util.List;

/// SQL Server 2022 方言：`top (1)` 单行上限、`offset ... rows fetch next ...` 分页、
/// `avg(cast(x as float))`、`like ... escape '\'`；锁显式拒绝（没有等价的 `for update`）。
public final class SqlServerDialect implements JdbcDialect {

    @Override
    public String name() {
        return "sqlserver";
    }

    /// T-SQL 方括号，内部 `]` 双写转义；双引号在 MSSQL 也可用，但方括号不依赖 `QUOTED_IDENTIFIER`。
    @Override
    public String quote(String identifier) {
        return '[' + identifier.replace("]", "]]") + ']';
    }

    /// exists 的单行上限放 SELECT 头（T-SQL 没有可参数化的 LIMIT 尾缀）。
    @Override
    public String selectHead(String columns, boolean exists) {
        return exists ? "select top (1) " + columns : "select " + columns;
    }

    /// `offset ... rows [fetch next ? rows only]`；`OFFSET` 前必须有 ORDER BY，无用户排序时补 `order by 1`。
    ///
    /// @param window      窗口
    /// @param exists      是否是 exists 查询（上限在 `selectHead` 的 `top (1)`，这里返回空串）
    /// @param hasOrderBy  本次 SQL 是否已有 ORDER BY
    /// @param parameters  窗口参数出口（先 offset 后 limit）
    /// @return 语句尾片段
    @Override
    public String limitClause(Window window, boolean exists, boolean hasOrderBy, SqlParameterSink parameters) {
        if (exists) {
            return "";
        }
        Integer offset = window == null ? null : window.offset();
        Integer limit = window == null ? null : window.limit();
        boolean hasOffset = offset != null && offset > 0;
        boolean hasLimit = limit != null && limit > 0;
        if (!hasOffset && !hasLimit) {
            return "";
        }
        StringBuilder sql = new StringBuilder(hasOrderBy ? "" : " order by 1");
        if (hasOffset) {
            // T-SQL 的 offset 必须跟 rows
            sql.append(" offset ? rows");
            parameters.append(offset);
        } else {
            sql.append(" offset 0 rows");
        }
        if (hasLimit) {
            sql.append(" fetch next ? rows only");
            parameters.append(limit);
        }
        return sql.toString();
    }

    /// SQL Server 没有等价的 `for update`；`with (updlock, rowlock)` 在 autocommit 下不持锁，语义不等价 → 拒绝。
    ///
    /// @param mode    锁模式
    /// @param aliases 表别名列表
    /// @return 永不返回
    /// @throws UnsupportedOperationException 任何锁模式
    @Override
    public LockClause lock(LockMode mode, List<String> aliases) {
        throw new UnsupportedOperationException("SQL Server 不支持行锁（autocommit 下 with (updlock) 语义不等价）: " + mode);
    }

    @Override
    public String averageOf(String operand) {
        return "avg(cast(" + operand + " as float))";
    }

    @Override
    public String likeEscape() {
        return " escape '\\'";
    }
}
