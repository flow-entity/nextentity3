package io.github.nextentity.api;

/// 字符串函数算子：大小写、去空白、截取、长度。
///
/// 函数返回的仍是算子，因此可以继续链式取值再比较：
/// `where(Employee::getName).lower().contains("ann")`、`where(Employee::getName).length().gt(5)`。
/// 每多一次函数调用就多一层函数节点，谓词的主语由这些节点从内到外组成。
///
/// @param <T> 根实体类型
/// @param <B> 继续链式的步骤类型
public interface StringFunctions<T, B> {

    /// 转小写。
    ///
    /// @return 以"转小写的结果"为主语的字符串算子
    StringOperator<T, B> lower();

    /// 转大写。
    ///
    /// @return 以"转大写的结果"为主语的字符串算子
    StringOperator<T, B> upper();

    /// 去掉首尾空白。
    ///
    /// @return 以"去空白的结果"为主语的字符串算子
    StringOperator<T, B> trim();

    /// 从 `offset` 截到末尾。`offset` 从 0 起算。
    ///
    /// 与 `substring(offset, length)` 是两个重载而不是"传一个很大的 length"：末端形状在 SQL 里是不同的
    /// （`SUBSTRING(x, pos)` vs `SUBSTRING(x, pos, len)`），用上界数字假装省略会让后端下发
    /// 一个与数据库上限绑死的字面量。
    ///
    /// @param offset 起始位置，从 0 起算，非负
    /// @return 以"截取结果"为主语的字符串算子
    StringOperator<T, B> substring(int offset);

    /// 从 `offset` 起截 `length` 个字符。`offset` 从 0 起算。
    ///
    /// @param offset 起始位置，从 0 起算，非负
    /// @param length 长度，非负
    /// @return 以"截取结果"为主语的字符串算子
    StringOperator<T, B> substring(int offset, int length);

    /// 字符串长度。
    ///
    /// 返回的是数字算子（可比较、可在区间内），不是字符串算子。
    ///
    /// @return 以"长度"为主语的数字算子
    NumberOperator<T, B> length();
}
