package io.github.nextentity.jdbc.meta;

/// 枚举列的存储形态：决定字面量怎么绑定、列值怎么还原成枚举常量。
///
/// 公开不是因为它是 API，而是因为 `compile` 与 `exec` 分属两个包：渲染参数绑定需要它把
/// `Enum.name()` / `ordinal()` 写进 `parameters()`，行装配需要它把列值还原成枚举常量。
public enum EnumStorage {
    /// 非枚举列。
    NONE,
    /// `@Enumerated(EnumType.STRING)`：存 `Enum.name()`。
    NAME,
    /// `@Enumerated(EnumType.ORDINAL)` 或无注解（JPA 默认）：存 `Enum.ordinal()`。
    ORDINAL
}
