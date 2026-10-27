package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.EnumStorage;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// 属性路径到列引用的解析器：按需注册 `LEFT JOIN`，按路径前缀缓存别名。
///
/// 根表别名固定 `t0`，join 目标按首次出现顺序编号 `t1`、`t2`…；同一前缀只 join 一次，
/// 因此同一关联路径在 SELECT / WHERE / ORDER BY 里出现多次仍只产生一个 join。
/// 标识符引用（表名 / 列名）统一走方言 `quote`，别名 `tN` 不引用。
final class SqlPaths {

    /// 根表别名。
    static final String ROOT_ALIAS = "t0";

    private final JdbcMeta meta;
    private final JdbcDialect dialect;
    private final Class<?> rootType;
    private final Map<List<String>, String> aliases = new HashMap<>();
    private final List<String> aliasOrder = new ArrayList<>();
    private final StringBuilder joins = new StringBuilder();
    private int nextAlias = 1;

    SqlPaths(JdbcMeta meta, JdbcDialect dialect, Class<?> rootType) {
        this.meta = meta;
        this.dialect = dialect;
        this.rootType = rootType;
        this.aliasOrder.add(ROOT_ALIAS);
    }

    /// 解析一条属性路径。
    ///
    /// @param path 属性路径，非空
    /// @return 列引用（`tN.列名`）与末段属性
    /// @throws IllegalArgumentException     名字解析不到
    /// @throws UnsupportedOperationException 集合关联路径，或关联对象作为路径末段
    Resolved resolve(AttributePath path) {
        List<String> names = path.names();
        String alias = ROOT_ALIAS;
        Class<?> currentType = rootType;
        for (int i = 0; i < names.size() - 1; i++) {
            String name = names.get(i);
            JdbcEntityAttribute attribute = attribute(currentType, name);
            if (!attribute.isAssociation()) {
                throw unknownAttribute(currentType, name);
            }
            alias = join(alias, attribute, names.subList(0, i + 1));
            currentType = attribute.associationTarget();
        }
        JdbcEntityAttribute last = attribute(currentType, names.get(names.size() - 1));
        if (last.isAssociation()) {
            throw new UnsupportedOperationException(
                    "投影关联对象未支持: " + path.entityType().getName() + "." + String.join(".", names));
        }
        return new Resolved(column(alias, last), last);
    }

    /// 列引用：`别名.列名`，列名按方言引用。
    String column(String alias, JdbcEntityAttribute attribute) {
        return alias + "." + dialect.quote(attribute.column());
    }

    /// 已有 join 子句（` left join ... on ...` 拼接串）。
    String joins() {
        return joins.toString();
    }

    /// 本次 SELECT 实际出现的表别名，根表在前（锁方言用）。
    List<String> aliases() {
        return List.copyOf(aliasOrder);
    }

    private String join(String ownerAlias, JdbcEntityAttribute attribute, List<String> prefix) {
        List<String> key = List.copyOf(prefix);
        String cached = aliases.get(key);
        if (cached != null) {
            return cached;
        }
        JdbcEntityType<?> target = meta.entityType(attribute.associationTarget());
        String alias = "t" + nextAlias++;
        joins.append(" left join ")
                .append(dialect.quote(target.table()))
                .append(' ')
                .append(alias)
                .append(" on ")
                .append(column(alias, target.id()))
                .append(" = ")
                .append(ownerAlias)
                .append('.')
                .append(dialect.quote(attribute.joinColumn()));
        aliases.put(key, alias);
        aliasOrder.add(alias);
        return alias;
    }

    private JdbcEntityAttribute attribute(Class<?> type, String name) {
        JdbcEntityAttribute attribute = meta.entityType(type).attribute(name);
        if (attribute == null) {
            throw unknownAttribute(type, name);
        }
        if (attribute.isAssociation() && attribute.joinColumn() == null) {
            throw new UnsupportedOperationException("集合关联路径未支持: " + type.getName() + "." + name);
        }
        return attribute;
    }

    /// 未知属性的统一异常：根路径与关联路径共用，保证文案一致。
    private static IllegalArgumentException unknownAttribute(Class<?> type, String name) {
        return new IllegalArgumentException("未知属性: " + type.getName() + "." + name);
    }

    /// 列的结果类型：枚举按存储形态折算（`NAME` → `String`，`ORDINAL`/无注解 → `Integer`），
    /// 其余取 Java 类型。实体列、路径投影、聚合与算术左操作数都从这里取。
    ///
    /// @param attribute 属性
    /// @return 结果列静态类型
    static Class<?> columnType(JdbcEntityAttribute attribute) {
        if (!attribute.type().isEnum()) {
            return attribute.type();
        }
        return attribute.enumStorage() == EnumStorage.NAME ? String.class : Integer.class;
    }

    /// 解析结果：列引用与末段属性（静态类型、枚举存储都从属性读）。
    ///
    /// @param expression `tN.列名`
    /// @param attribute  末段属性
    record Resolved(String expression, JdbcEntityAttribute attribute) {}
}
