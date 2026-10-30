package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.util.ImmutableList;
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
@SuppressWarnings("PMD.CyclomaticComplexity") // 分支数由属性形态（标量 / 嵌入 / 关联 / 路径深度）决定，拆开会把同一个路径渲染点散成多段
final class SqlPaths {

    /// 根表别名。
    static final String ROOT_ALIAS = "t0";

    private final JdbcMeta meta;
    private final JdbcDialect dialect;
    private final Class<?> rootType;
    /// 条件改删（`UPDATE` / `DELETE`）的根实体：非 null 时进入「扁平」模式——只解析根表列，
    /// 不生成 join、不带别名（`UPDATE t SET` / `DELETE FROM t` 的列引用不能带 `tN.` 前缀）。
    private final JdbcEntityType<?> flatRoot;
    private final Map<List<String>, String> aliases = new HashMap<>();
    private final List<String> aliasOrder = new ArrayList<>();
    private final StringBuilder joins = new StringBuilder();
    private int nextAlias = 1;

    SqlPaths(JdbcMeta meta, JdbcDialect dialect, Class<?> rootType) {
        this(meta, dialect, rootType, null);
    }

    SqlPaths(JdbcEntityType<?> root, JdbcDialect dialect) {
        this(null, dialect, root.type(), root);
    }

    private SqlPaths(JdbcMeta meta, JdbcDialect dialect, Class<?> rootType, JdbcEntityType<?> flatRoot) {
        this.meta = meta;
        this.dialect = dialect;
        this.rootType = rootType;
        this.flatRoot = flatRoot;
        this.aliasOrder.add(ROOT_ALIAS);
    }

    /// 解析一条属性路径。
    ///
    /// @param path 属性路径，非空
    /// @return 列引用（`tN.列名`）与末段属性
    /// @throws IllegalArgumentException     名字解析不到
    /// @throws UnsupportedOperationException 集合关联路径，或关联对象作为路径末段
    Resolved resolve(AttributePath path) {
        if (flatRoot != null) {
            return flat(path);
        }
        List<String> names = path.names();
        String alias = ROOT_ALIAS;
        Class<?> currentType = rootType;
        int index = 0;
        while (true) {
            String name = names.get(index);
            JdbcEntityAttribute attribute = attribute(currentType, name);
            if (attribute.isEmbedded()) {
                return embedded(path, alias, attribute, index);
            }
            if (index == names.size() - 1) {
                if (attribute.isAssociation()) {
                    throw new UnsupportedOperationException("投影关联对象未支持: " + describe(path));
                }
                return new Resolved(column(alias, attribute), attribute);
            }
            if (!attribute.isAssociation()) {
                throw unknownAttribute(currentType, name);
            }
            alias = join(alias, attribute, names.subList(0, index + 1));
            currentType = attribute.associationTarget();
            index++;
        }
    }

    /// 条件改删的扁平解析：只允许根表列与 `嵌入属性.字段` 两段；关联路径要 join，`UPDATE`/`DELETE`
    /// 里没有可移植的写法（各库别名语法不同），一律明确拒绝而不是渲染出方言相关的 SQL。
    private Resolved flat(AttributePath path) {
        List<String> names = path.names();
        JdbcEntityAttribute attribute = flatRoot.attribute(names.getFirst());
        if (attribute == null) {
            throw unknownAttribute(rootType, names.getFirst());
        }
        if (attribute.isAssociation()) {
            throw new UnsupportedOperationException("条件改删不支持关联列: " + describe(path));
        }
        if (attribute.isEmbedded()) {
            if (names.size() != 2) {
                throw new UnsupportedOperationException("条件改删只支持嵌入字段列: " + describe(path));
            }
            JdbcEntityAttribute nested = attribute.embeddedColumn(names.get(1));
            if (nested == null) {
                throw unknownAttribute(attribute.embeddedTarget(), names.get(1));
            }
            return new Resolved(column(nested), nested);
        }
        if (names.size() != 1) {
            throw new UnsupportedOperationException("条件改删不支持关联路径: " + describe(path));
        }
        return new Resolved(column(attribute), attribute);
    }

    /// 不带别名的列引用（条件改删的 `SET` / `WHERE` 用）。
    String column(JdbcEntityAttribute attribute) {
        return attribute.isColumnQuoted() ? dialect.quote(attribute.column()) : attribute.column();
    }

    /// `@Embedded` 路径段：必须是 `外层属性.嵌入字段名` 两段收尾（嵌入列已扁平化成一列）。
    private Resolved embedded(AttributePath path, String alias, JdbcEntityAttribute attribute, int index) {
        List<String> names = path.names();
        if (index + 1 >= names.size()) {
            throw new UnsupportedOperationException("嵌入对象不能整体投影: " + describe(path));
        }
        String nestedName = names.get(index + 1);
        JdbcEntityAttribute nested = attribute.embeddedColumn(nestedName);
        if (nested == null) {
            throw unknownAttribute(attribute.embeddedTarget(), nestedName);
        }
        if (index + 2 != names.size()) {
            throw new UnsupportedOperationException("嵌入属性不支持再下钻: " + describe(path));
        }
        return new Resolved(column(alias, nested), nested);
    }

    private static String describe(AttributePath path) {
        return path.entityType().getName() + "." + String.join(".", path.names());
    }

    /// 列引用：`别名.列名`；只有注解用反引号标记过的列名才走方言 `quote`，否则保持裸名。
    String column(String alias, JdbcEntityAttribute attribute) {
        String name = attribute.isColumnQuoted() ? dialect.quote(attribute.column()) : attribute.column();
        return alias + "." + name;
    }

    /// 已有 join 子句（` left join ... on ...` 拼接串）。
    String joins() {
        return joins.toString();
    }

    /// 本次 SELECT 实际出现的表别名，根表在前（锁方言用）。
    List<String> aliases() {
        return ImmutableList.copyOf(aliasOrder);
    }

    private String join(String ownerAlias, JdbcEntityAttribute attribute, List<String> prefix) {
        List<String> key = ImmutableList.copyOf(prefix);
        String cached = aliases.get(key);
        if (cached != null) {
            return cached;
        }
        JdbcEntityType<?> target = meta.entityType(attribute.associationTarget());
        String alias = "t" + nextAlias++;
        String table = target.isTableQuoted() ? dialect.quote(target.table()) : target.table();
        String joinColumn =
                attribute.isJoinColumnQuoted() ? dialect.quote(attribute.joinColumn()) : attribute.joinColumn();
        joins.append(" left join ")
                .append(table)
                .append(' ')
                .append(alias)
                .append(" on ")
                .append(column(alias, target.id()))
                .append(" = ")
                .append(ownerAlias)
                .append('.')
                .append(joinColumn);
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

    /// 列的结果类型：`@Convert` 列取转换器的数据库类型（行读取先按它收窄再经转换器还原），
    /// 枚举按存储形态折算（`NAME` → `String`，`ORDINAL`/无注解 → `Integer`），其余取 Java 类型。
    /// 实体列、路径投影、聚合与算术左操作数都从这里取。
    ///
    /// @param attribute 属性
    /// @return 结果列静态类型
    static Class<?> columnType(JdbcEntityAttribute attribute) {
        if (attribute.converter() != null) {
            return attribute.databaseType();
        }
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
