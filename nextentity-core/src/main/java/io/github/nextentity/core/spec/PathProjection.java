package io.github.nextentity.core.spec;

import java.util.Objects;

/// 裸路径投影项。
///
/// `embeddedFallback` 是后端能力开关：只有 `select(Dto.class)` 的列集（DTO 属性名 = 实体属性名，
/// 嵌平字段在实体上没有同名属性）置 `true`，允许后端按名到实体的 `@Embedded` 组件里回找同名叶子
/// （`street` → `address.street`）。显式投影（`select(Path.of(...))` / `project(ProjectionMapper)`）
/// 的列是调用方**显式写出**的路径，置 `false`：解析不到属性就该在渲染时报错，静默改写成嵌入列
/// 会让写错的路径读成另一列。缺省 `false`，后端不支持回找时可以忽略该标记。
///
/// @param path             属性路径
/// @param embeddedFallback 是否允许后端按名回找 `@Embedded` 同名叶子
public record PathProjection(AttributePath path, boolean embeddedFallback) implements Projection {

    /// 紧凑构造器：path 必须非 `null`。
    public PathProjection {
        Objects.requireNonNull(path, "path");
    }

    /// 显式投影列：不允许嵌入回找（历史构造形态，语义不变）。
    ///
    /// @param path 属性路径
    public PathProjection(AttributePath path) {
        this(path, false);
    }
}
