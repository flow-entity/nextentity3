package io.github.nextentity.core.spec;

import io.github.nextentity.core.util.ImmutableList;
import java.util.List;
import java.util.Objects;

/// 属性路径：从实体根出发的一串属性名，例如 `customer.orgId`。
///
/// 单段路径表示实体自有字段，多段路径表示跨关联的嵌套字段（渲染时可能需要 join）。
/// 它同时是 [Operand] 的一种：条件两侧、投影、排序项都复用它。
///
/// @param entityType 路径起点实体类型
/// @param names      属性名序列，非空
public record AttributePath(Class<?> entityType, List<String> names) implements Operand {

    /// 紧凑构造器：校验入参并复制为不可变列表。
    public AttributePath {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(names, "names");
        if (names.isEmpty()) {
            throw new IllegalArgumentException("属性路径不能为空");
        }
        names = ImmutableList.copyOf(names);
    }

    /// 由实体类型与属性名序列构造路径。
    ///
    /// @param entityType 实体类型
    /// @param names      属性名序列
    /// @return 属性路径
    public static AttributePath of(Class<?> entityType, String... names) {
        return new AttributePath(entityType, List.of(names));
    }

    /// 路径深度。
    ///
    /// @return 属性名个数
    public int depth() {
        return names.size();
    }

    /// 是否为实体自有字段（单段路径）。
    ///
    /// @return 单段返回 true
    public boolean isDirect() {
        return names.size() == 1;
    }
}
