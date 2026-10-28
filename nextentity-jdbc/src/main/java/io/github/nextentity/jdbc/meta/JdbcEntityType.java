package io.github.nextentity.jdbc.meta;

import io.github.nextentity.core.meta.EntityType;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

/// `EntityType` 的注解扫描实现：表名、属性表、`@Id` / `@Version` 句柄。
///
/// 解析一次后缓存（见 [JdbcMeta]）。`attributes()` 含 `@ManyToOne` 关联属性（路径解析需要），
/// **不含** §4.3 #9 被忽略的集合/一对一关联字段；[JdbcEntityAttribute#joinColumn()] 为 `null`
/// 的关联属性就是被忽略的那种。
///
/// @param <T> 实体类型
public final class JdbcEntityType<T> implements EntityType<T> {

    private final Class<T> type;
    private final String table;
    private final boolean tableQuoted;
    private final List<JdbcEntityAttribute> attributes;
    private final Map<String, JdbcEntityAttribute> attributesByName;
    private final JdbcEntityAttribute id;
    private final JdbcEntityAttribute version;
    private final Constructor<T> constructor;

    private JdbcEntityType(JdbcEntityScan.Scanned<T> scanned) {
        this.type = scanned.type();
        this.table = scanned.table();
        this.tableQuoted = scanned.tableQuoted();
        this.attributes = scanned.attributes();
        this.attributesByName = scanned.attributesByName();
        this.id = scanned.id();
        this.version = scanned.version();
        this.constructor = scanned.constructor();
    }

    /// 解析实体类型。
    ///
    /// @param type 实体类型；缺 `@Entity` / 缺 `@Id` 等按 §4.3 #1–#14 拒绝
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

    /// 按属性名取属性；含被 §4.3 #9 忽略的关联属性（`joinColumn()` 为 `null`），未知名字返回 `null`。
    public JdbcEntityAttribute attribute(String attributeName) {
        return attributesByName.get(attributeName);
    }

    /// 按属性名取列名；未知名字返回 `null`（校验与报错留给渲染层，见 §3.10）。
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
}
