package io.github.nextentity.core.spec;

import java.util.Objects;

/// 字符串截取：`SUBSTRING(subject, offset[, length])`。
///
/// `offset` 从 0 起算（与 [io.github.nextentity.api.StringFunctions#substring] 一致），
/// 渲染成 SQL 时由后端决定从 0 还是从 1 起算。
///
/// [length] 为 `null` 表示"截到末尾"（`SUBSTRING(x, pos)`），而不是一个很大的上界数字：
/// 后者会把「省略长度」伪装成「截这么多字符」，与数据库的字符串上限绑死。
///
/// @param subject 主语操作数
/// @param offset  起始位置，从 0 起算，非负
/// @param length  长度，非负；`null` 表示截到末尾
public record SubstringOperand(Operand subject, int offset, Integer length) implements Operand {

    /// 紧凑构造器：主语必填，位置与长度都不得为负（负数是调用方写错，不该静默裁成 0）。
    public SubstringOperand {
        Objects.requireNonNull(subject, "subject");
        if (offset < 0) {
            throw new IllegalArgumentException("substring 的 offset 不能为负数: " + offset);
        }
        if (length != null && length < 0) {
            throw new IllegalArgumentException("substring 的 length 不能为负数: " + length);
        }
    }
}
