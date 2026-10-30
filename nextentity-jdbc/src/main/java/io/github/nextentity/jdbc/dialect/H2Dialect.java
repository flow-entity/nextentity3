package io.github.nextentity.jdbc.dialect;

import io.github.nextentity.api.LockMode;
import java.util.List;

/// H2（MODE=MySQL）方言。
public final class H2Dialect implements JdbcDialect {

    @Override
    public String name() {
        return "h2";
    }

    /// H2 双引号：与 Hibernate 在 H2 上的渲染一致（Lead 裁决），内部 `"` 双写转义。
    @Override
    public String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /// H2 没有 `FOR SHARE`：读锁退化为更强的 `for update`（写锁），语义安全但会阻塞并发读，
    /// **不是** MySQL / PostgreSQL 共享锁的等价形态（设计 §6.1 方言差异，已报 architect 写进 USAGE §13.4）。
    @Override
    public LockClause lock(LockMode mode, List<String> aliases) {
        return new LockClause("", " for update");
    }
}
