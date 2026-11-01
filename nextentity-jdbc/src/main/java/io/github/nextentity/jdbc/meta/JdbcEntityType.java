package io.github.nextentity.jdbc.meta;

import io.github.nextentity.core.meta.EntityType;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// `EntityType` 的注解扫描实现：表名、属性表、`@Id` / `@Version` 句柄。
///
/// 解析一次后缓存（见 [JdbcMeta]）。`attributes()` 含 `@ManyToOne` 关联属性（路径解析需要），
/// **不含**被静默忽略的集合/反侧一对一关联字段；[JdbcEntityAttribute#joinColumn()] 为 `null`
/// 的关联属性就是被忽略的那种。
///
/// @param <T> 实体类型
public final class JdbcEntityType<T> implements EntityType<T> {

    private final Class<T> type;
    private final String table;
    private final boolean tableQuoted;
    private final List<JdbcEntityAttribute> attributes;
    private final List<JdbcEntityAttribute> columns;
    private final Map<String, JdbcEntityAttribute> attributesByName;
    private final JdbcEntityAttribute id;
    private final JdbcEntityAttribute version;
    private final Constructor<T> constructor;

    private JdbcEntityType(JdbcEntityScan.Scanned<T> scanned) {
        this.type = scanned.type();
        this.table = scanned.table();
        this.tableQuoted = scanned.tableQuoted();
        this.attributes = scanned.attributes();
        this.columns = flattened(scanned.type(), scanned.attributes());
        this.attributesByName = scanned.attributesByName();
        this.id = scanned.id();
        this.version = scanned.version();
        this.constructor = scanned.constructor();
    }

    /// 解析实体类型。
    ///
    /// @param type 实体类型；映射不合法时抛异常
    static <T> JdbcEntityType<T> of(Class<T> type) {
        return new JdbcEntityType<>(JdbcEntityScan.scan(type));
    }

    @Override
    public Class<T> type() {
        return type;
    }

    /// 表名：`@Table(name)` → `@Entity(name)` → 类简名；已剥反引号（裸名）。
    public String table() {
        return table;
    }

    /// 表名是否需要在 SQL 中引用（注解里用反引号包裹过）：引用形态由 `JdbcDialect#quote` 决定。
    public boolean isTableQuoted() {
        return tableQuoted;
    }

    /// 全部持久属性（声明序，标量 + `@ManyToOne`）：实体 SELECT 的列序与写侧 SET 列序都从这里取，
    /// 各自再过滤 `!isAssociation()`。
    public List<JdbcEntityAttribute> attributes() {
        return attributes;
    }

    /// 扁平化的列属性（声明序）：`@Embedded` 属性展开为嵌入类型里的列属性，其余属性原样保留（含关联）。
    ///
    /// 与 [#attributes()] 的差别只在 `@Embedded`：SELECT 列集、INSERT / UPDATE 列集与行装配都走这里，
    /// 路径解析（`address.city`）仍按 `attributes()` 逐段下钻。
    public List<JdbcEntityAttribute> columns() {
        return columns;
    }

    /// 按属性名取属性；含被静默忽略的关联属性（`joinColumn()` 为 `null`），未知名字返回 `null`。
    public JdbcEntityAttribute attribute(String attributeName) {
        return attributesByName.get(attributeName);
    }

    /// 按属性名取列名；未知名字返回 `null`（校验与报错留给渲染层 SqlPaths）。
    public String column(String attributeName) {
        JdbcEntityAttribute attribute = attributesByName.get(attributeName);
        return attribute == null ? null : attribute.column();
    }

    @Override
    public JdbcEntityAttribute id() {
        return id;
    }

    @Override
    public JdbcEntityAttribute version() {
        return version;
    }

    /// 反射建实体实例（无参构造，`protected` 也已放行）。
    public T newInstance() {
        try {
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("实例化实体失败: " + type.getName(), e);
        }
    }

    /// 扁平化后同一列名只允许出现一次：重复会让 SELECT 把同一列读进两个属性、写入互相覆盖（静默错数据）。
    ///
    /// 例外是 legacy `Employee` 的同列双映射——标量字段与**只读**关联（`@JoinColumn(insertable = false,
    /// updatable = false)`）指向同一列：写入只走可写的那一侧，语句里该列只出现一次，不是错数据。
    /// 两侧都可写时才是真冲突（`insert into t (id, c, c)` 由数据库报错）。
    private static List<JdbcEntityAttribute> flattened(Class<?> type, List<JdbcEntityAttribute> attributes) {
        List<JdbcEntityAttribute> columns = new ArrayList<>();
        for (JdbcEntityAttribute attribute : attributes) {
            if (attribute.isEmbedded()) {
                columns.addAll(attribute.embeddedColumns());
            } else {
                columns.add(attribute);
            }
        }
        Map<String, JdbcEntityAttribute> byColumn = new HashMap<>();
        for (JdbcEntityAttribute column : columns) {
            String name = column.column() != null ? column.column() : column.joinColumn();
            if (name != null && writableDuplicate(byColumn.putIfAbsent(name, column), column)) {
                throw new UnsupportedOperationException(
                        "同一实体内列名重复: " + type.getName() + " -> " + name + "（同一可嵌入类型多次嵌入请用 @AttributeOverride 指定不同列名）");
            }
        }
        return List.copyOf(columns);
    }

    /// 同一列名的第二次映射：只有「两侧都可写」才是冲突（只读的那一侧写侧本来就跳过）。
    ///
    /// 判据只看**可写侧的数量**，与哪一侧是关联无关：legacy `Employee` 是「可写标量 + 只读外键」，
    /// 镜像形态是「只读标量 + 可写外键」，两者都只有一侧进写语句、`SELECT` 也只投影标量列
    /// （`SqlProjections` 跳过全部关联列），该列在整句 SQL 里只出现一次。旧实现只把「先出现的是
    /// 只读关联」写成特例，标量侧没有对应分支，于是同一规则在两个声明方向上结论相反。
    private static boolean writableDuplicate(JdbcEntityAttribute previous, JdbcEntityAttribute column) {
        return previous != null && previous.isWritable() && column.isWritable();
    }
}
