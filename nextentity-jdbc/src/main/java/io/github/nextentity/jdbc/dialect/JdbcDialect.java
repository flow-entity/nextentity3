package io.github.nextentity.jdbc.dialect;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.core.spec.Window;
import java.util.List;

/// 对外可实现的方言接缝：实现本接口并 `new JdbcEntityOperationsFactory(dataSource, dialect)` 即完成自定义方言接入。
///
/// 共 10 个成员：静态 `of(String)` + 9 个实例钩子（`name` / `quote` / `selectHead` / `limitClause` /
/// `lock` / `averageOf` / `likeEscape` / `lengthOf` / `substring`）。除 [JdbcDialect#name()] 外全部实例钩子
/// 有默认实现，默认形态 = H2 / MySQL / PostgreSQL；SQL Server 覆写 `selectHead` / `limitClause` / `lock` /
/// `averageOf` / `likeEscape` / `lengthOf` / `substring`。钩子语义与绑定契约见设计 §6.1，
/// 允许的渲染点见 §6.3——不在那列的差异不得加钩子。
public interface JdbcDialect {

    /// 内置名：`"h2"` / `"mysql"` / `"postgresql"` / `"sqlserver"`；自定义方言返回自己的名（仅用于日志/错误）。
    ///
    /// @return 方言名
    String name();

    /// 产品名 → 内置实现：忽略大小写；`PostgreSQL 16.2` / `Microsoft SQL Server 2022` 按前缀容错。
    ///
    /// @param databaseProductName `DatabaseMetaData#getDatabaseProductName()`，可为 null
    /// @return 对应方言
    /// @throws UnsupportedOperationException 产品名不受支持（消息引导传入自定义实现）
    static JdbcDialect of(String databaseProductName) {
        String product = databaseProductName == null ? "" : databaseProductName.trim();
        if ("MySQL".equalsIgnoreCase(product)) {
            return new MySqlDialect();
        }
        if ("H2".equalsIgnoreCase(product)) {
            return new H2Dialect();
        }
        if (startsWithIgnoreCase(product, "PostgreSQL")) {
            return new PostgresDialect();
        }
        if (startsWithIgnoreCase(product, "Microsoft SQL Server")) {
            return new SqlServerDialect();
        }
        throw new UnsupportedOperationException(
                "内置支持 H2 / MySQL / PostgreSQL / SQL Server；其它产品请实现 JdbcDialect 并通过 (DataSource, JdbcDialect) 传入："
                        + databaseProductName);
    }

    /// 标识符引用；默认恒等（自定义方言可保持不引用）。内置四方言实现：H2 / PostgreSQL 双引号、
    /// MySQL 反引号、SQL Server 方括号，内部同字符双写转义。
    ///
    /// 只有 meta 标记为 quoted 的名字（注解名用反引号包裹过）才会调用本方法，未 quoted 一律裸名，
    /// 因此默认方言下既有 SQL 零变化。调用点（设计 §6.3）：SELECT 列 / FROM 表 / JOIN 目标表与 ON
    /// 两侧列 / WHERE·GROUP BY·HAVING·ORDER BY 的列引用。
    ///
    /// @param identifier 裸标识符（表名 / 列名）
    /// @return 引用后的标识符
    default String quote(String identifier) {
        return identifier;
    }

    /// 完整 SELECT 头：默认 `select <columns>`；SQL Server 的 exists 用 `select top (1) <columns>`。
    ///
    /// @param columns 已渲染的 SELECT 列表
    /// @param exists  是否是 exists 查询（单行上限）
    /// @return SELECT 头片段
    default String selectHead(String columns, boolean exists) {
        return "select " + columns;
    }

    /// 语句尾部窗口片段；窗口参数由方言按自己的顺序 append，renderer 不解析占位符位置。
    ///
    /// @param window      窗口，可能为 [Window#NONE]
    /// @param exists      是否是 exists 查询（默认渲染字面量 ` limit 1`，不绑参）
    /// @param hasOrderBy  本次 SQL 是否已有 ORDER BY（false 时要求排序的方言自行补）
    /// @param parameters  窗口参数出口
    /// @return 语句尾片段（` limit ?` / ` limit ? offset ?` / 空串）
    default String limitClause(Window window, boolean exists, boolean hasOrderBy, SqlParameterSink parameters) {
        if (exists) {
            return " limit 1";
        }
        Integer offset = window == null ? null : window.offset();
        Integer limit = window == null ? null : window.limit();
        boolean hasOffset = offset != null && offset > 0;
        boolean hasLimit = limit != null && limit > 0;
        if (hasLimit) {
            parameters.append(limit);
        } else if (hasOffset) {
            // limit 缺省但给了 offset：MySQL/H2/PG 都要求 LIMIT 在前，用哨兵表示不限行
            parameters.append(Long.MAX_VALUE);
        }
        if (!hasOffset) {
            return hasLimit ? " limit ?" : "";
        }
        parameters.append(offset);
        return " limit ? offset ?";
    }

    /// 锁片段；aliases = 本次 SELECT 实际出现的表别名，根表在前。
    ///
    /// 默认按模式分派：`PESSIMISTIC_WRITE` → `for update`，`PESSIMISTIC_READ` → `for share`
    /// （MySQL / PostgreSQL 的共享锁形态）。H2 无 `FOR SHARE`，覆写为更强的 `for update` 兜底；
    /// SQL Server 无等价行锁，覆写为拒绝。
    ///
    /// @param mode    锁模式（renderer 已保证只传 `PESSIMISTIC_WRITE` / `PESSIMISTIC_READ`）
    /// @param aliases 表别名列表（根别名在前）
    /// @return 锁片段；不支持锁的方言必须抛 [UnsupportedOperationException]
    default LockClause lock(LockMode mode, List<String> aliases) {
        return switch (mode) {
            case PESSIMISTIC_WRITE -> new LockClause("", " for update");
            case PESSIMISTIC_READ -> new LockClause("", " for share");
            case OPTIMISTIC, OPTIMISTIC_FORCE_INCREMENT, PESSIMISTIC_FORCE_INCREMENT ->
                throw new UnsupportedOperationException("方言 " + name() + " 不支持锁模式: " + mode);
        };
    }

    /// 聚合表达式：入参是已渲染的列表达式（含别名与 quote）。默认 `avg(x)`。
    ///
    /// @param operand 已渲染的聚合操作数
    /// @return AVG 表达式
    default String averageOf(String operand) {
        return "avg(" + operand + ")";
    }

    /// LIKE 谓词尾的转义子句；默认空串。SQL Server 需要 ` escape '\'`。
    ///
    /// @return 追加在 `like ?` 之后的片段
    default String likeEscape() {
        return "";
    }

    /// `length` 函数形态：默认 `length(x)`（H2 / MySQL / PostgreSQL 同形）。
    ///
    /// @param operand 已渲染的字符串操作数
    /// @return LENGTH 表达式
    default String lengthOf(String operand) {
        return "length(" + operand + ")";
    }

    /// `substring` 形态：`position` 从 1 起算（调用方已把 API 的 0 起算偏移 +1），
    /// `length == null` 表示「截到结尾」。
    ///
    /// 默认渲染 `substring(x, pos)` / `substring(x, pos, len)`（H2 / MySQL / PostgreSQL 同形）。
    ///
    /// @param operand  已渲染的字符串操作数
    /// @param position 1 起算的起始位置
    /// @param length   截取长度；`null` = 到结尾
    /// @return SUBSTRING 表达式
    default String substring(String operand, int position, Integer length) {
        String tail = length == null ? ")" : ", " + length + ")";
        return "substring(" + operand + ", " + position + tail;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
