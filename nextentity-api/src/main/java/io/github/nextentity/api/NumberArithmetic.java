package io.github.nextentity.api;

/// 数值算术：`列 ± 值`、`列 × 值`、`列 ÷ 值`、`列 mod 值`。
///
/// 每个方法返回 [NumberValue]，因此算术可以继续链式叠加（`salary * 12 + 1000`），
/// 也可以直接进 `select(...)` 投影或参与比较。
///
/// @param <T> 根实体类型
/// @param <U> 数值类型
public interface NumberArithmetic<T, U extends Number> {

    /// 加上一个值。
    ///
    /// @param addend 加数，非 `null`
    /// @return 算术结果表达式
    NumberValue<T, U> add(U addend);

    /// 减去一个值。
    ///
    /// @param subtrahend 减数，非 `null`
    /// @return 算术结果表达式
    NumberValue<T, U> sub(U subtrahend);

    /// 乘以一个值。
    ///
    /// @param multiplier 乘数，非 `null`
    /// @return 算术结果表达式
    NumberValue<T, U> mul(U multiplier);

    /// 除以一个值。
    ///
    /// 除数为 0 的行为与数据库一致（移植到 java 的 `/` 语义不适用）。
    ///
    /// @param divisor 除数，非 `null`
    /// @return 算术结果表达式
    NumberValue<T, U> div(U divisor);

    /// 对一个值取模。
    ///
    /// @param divisor 模数，非 `null`
    /// @return 算术结果表达式
    NumberValue<T, U> mod(U divisor);
}
