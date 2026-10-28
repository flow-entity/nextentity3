package io.github.nextentity.jdbc.dialect;

import io.github.nextentity.api.LockMode;
import java.util.List;

/// PostgreSQL 16 方言：其余钩子沿用默认形态（`limit ? offset ?`、`avg`、无 LIKE 转义）。
///
/// 锁差异：LEFT JOIN 下裸 `for update` 报错，必须 `for update of <根表别名>`（probe 实证）。
public final class PostgresDialect implements JdbcDialect {

    @Override
    public String name() {
        return "postgresql";
    }

    /// PostgreSQL 双引号，内部 `"` 双写转义。
    @Override
    public String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    @Override
    public LockClause lock(LockMode mode, List<String> aliases) {
        return new LockClause("", " for update of " + aliases.get(0));
    }
}
