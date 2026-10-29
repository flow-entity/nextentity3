package io.github.nextentity.jdbc.meta;

import io.github.nextentity.core.util.ImmutableList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// 属性收集器：按声明序累加属性，并做 §4.3 #2 的 `@Id` 唯一性、#6 的 `@Version` 唯一性检查。
final class JdbcAttributes {

    private final Class<?> type;
    private final List<JdbcEntityAttribute> attributes = new ArrayList<>();
    private final Map<String, JdbcEntityAttribute> byName = new LinkedHashMap<>();
    private JdbcEntityAttribute id;
    private JdbcEntityAttribute version;

    JdbcAttributes(Class<?> type) {
        this.type = type;
    }

    void add(Field field) {
        JdbcEntityAttribute attribute = JdbcEntityRules.attribute(type, field);
        byName.put(attribute.name(), attribute);
        if (attribute.isId()) {
            if (id != null) {
                throw JdbcEntityRules.idMissing(type);
            }
            id = attribute;
        } else if (attribute.isVersion()) {
            if (version != null) {
                throw JdbcEntityRules.duplicateVersion(type, field);
            }
            version = attribute;
        }
        if (!attribute.isAssociation() || attribute.joinColumn() != null) {
            attributes.add(attribute);
        }
    }

    /// 持久属性（标量 + 可渲染的 `@ManyToOne`），声明序；§4.3 #9 被忽略的关联不在其中。
    List<JdbcEntityAttribute> attributes() {
        return ImmutableList.copyOf(attributes);
    }

    Map<String, JdbcEntityAttribute> byName() {
        return Collections.unmodifiableMap(byName);
    }

    JdbcEntityAttribute id() {
        if (id == null) {
            throw JdbcEntityRules.idMissing(type);
        }
        return id;
    }

    JdbcEntityAttribute version() {
        return version;
    }
}
