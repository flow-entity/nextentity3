package io.github.nextentity.core.spec;

/// 恒真/恒假节点：`1=1` / `1=0`。
///
/// 唯一来源是集合算子遇到空集合（2.2.3 `ExpressionNode:78-88`：`IN` 空 → `FALSE`，
/// 再套一层 `NOT` 即 `TRUE`）。有了它，空集合语义在 AST 里是显式的，
/// 后端只需把 `Constant` 渲染成 `conjunction()` / `disjunction()`，不必再猜。
///
/// @param value `true` 表示恒真
public record Constant(boolean value) implements Spec {

    /// 恒真节点。
    public static final Constant TRUE = new Constant(true);

    /// 恒假节点。
    public static final Constant FALSE = new Constant(false);

    /// 按布尔值取单例。
    ///
    /// @param value 布尔值
    /// @return `TRUE` 或 `FALSE` 单例：同一布尔值永远拿到同一实例，可用 `==` 比较
    public static Constant of(boolean value) {
        return value ? TRUE : FALSE;
    }
}
