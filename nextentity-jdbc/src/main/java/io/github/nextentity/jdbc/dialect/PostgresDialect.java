package io.github.nextentity.jdbc.dialect;

import io.github.nextentity.api.LockMode;
import java.util.List;

/// PostgreSQL 16 方言：其余钩子沿用默认形态（`limit ? offset ?`、`avg`、无 LIKE 转义）。
///
/// 锁差异：LEFT JOIN 下裸 `for update` 报错，必须 `for update of <根表别名>`（probe 实证）；
/// 共享锁同理用 `for share of <根表别名>`。
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
        String root = aliases.get(0);
        return switch (mode) {
            case PESSIMISTIC_WRITE -> new LockClause("", " for update of " + root);
            case PESSIMISTIC_READ -> new LockClause("", " for share of " + root);
            // 输入域不可达：SqlClauses.lock 在调用方言钩子前只放行 PESSIMISTIC_WRITE / PESSIMISTIC_READ；
            // 这里保留防御，让直接调用钩子的自定义方言实现者也 fail-fast。
            case OPTIMISTIC, OPTIMISTIC_FORCE_INCREMENT, PESSIMISTIC_FORCE_INCREMENT ->
                throw new UnsupportedOperationException("方言 " + name() + " 不支持锁模式: " + mode);
        };
    }
}
