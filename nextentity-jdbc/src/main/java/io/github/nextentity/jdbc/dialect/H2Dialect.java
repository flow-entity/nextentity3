package io.github.nextentity.jdbc.dialect;

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
}
