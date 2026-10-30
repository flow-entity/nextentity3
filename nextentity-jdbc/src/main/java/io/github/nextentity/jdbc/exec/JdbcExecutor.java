package io.github.nextentity.jdbc.exec;

import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/// JDBC 执行器：跑 {@link JdbcQuery} 交回的语句，行装配交给 [RowReader]。
///
/// 与 JPA 侧一样只交**原始行**；`count` / `exists` 的语句由 [JdbcCompiler] 单独渲染，这里不拼 SQL。
/// 每次操作独立取连接（不缓存、不开事务），连接在 try-with-resources 里关掉。
public final class JdbcExecutor implements Executor<JdbcQuery> {

    private static final String EXECUTION_FAILED = "SQL 执行失败: ";

    private final DataSource dataSource;
    private final JdbcCompiler compiler;
    private final RowReader reader;

    /// 创建执行器。
    ///
    /// @param dataSource 数据源（每次操作独立取连接）
    /// @param meta       实体元数据（行装配用）
    /// @param compiler   语句编译器（`count` / `exists` 的语句由它渲染）
    public JdbcExecutor(DataSource dataSource, JdbcMeta meta, JdbcCompiler compiler) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.reader = new RowReader(meta);
    }

    /// 执行列表查询，按查询形态交出原始行。
    @Override
    public <R> List<R> list(JdbcQuery query) {
        return read(query.statement(), rows -> reader.read(rows, query));
    }

    /// 执行计数查询（语句由编译器渲染，`forCount()` 已清排序 / 窗口 / 锁）。
    @Override
    public long count(JdbcQuery query) {
        // `count(*)` / `count(distinct col)` 恒返回一行，`next()` 为假只是防御分支，正常路径不可达。
        return read(compiler.count(query.spec()), rows -> rows.next() ? rows.getLong(1) : 0L);
    }

    /// 执行存在性查询（语句由编译器渲染，`forExists()` 已把窗口置成 1 行）。
    @Override
    public boolean exists(JdbcQuery query) {
        return read(compiler.exists(query.spec()), ResultSet::next);
    }

    private <T> T read(SqlStatement statement, ResultReader<T> reader) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement prepared = connection.prepareStatement(statement.sql())) {
            bind(prepared, statement.parameters());
            try (ResultSet rows = prepared.executeQuery()) {
                return reader.read(rows);
            }
        } catch (SQLException e) {
            throw new PersistenceException(EXECUTION_FAILED + statement.sql(), e);
        }
    }

    /// 按参数顺序绑定 `setObject`；`null` 也走 `setObject`（两方言实测可，见设计 §5.1）。
    static void bind(PreparedStatement prepared, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            prepared.setObject(i + 1, parameters.get(i));
        }
    }

    @FunctionalInterface
    private interface ResultReader<T> {

        T read(ResultSet rows) throws SQLException;
    }
}
