package io.github.nextentity.core.spec;

import java.util.List;
import java.util.Objects;

/// 逻辑组合节点：`AND/OR/NOT(子节点...)`。
///
/// 保证「至少一个操作数」，且 `NOT` 只接受单个操作数——非法结构在构造期即被拒绝，
/// 编译器因此不必处理畸形输入。
///
/// @param operator 逻辑算子
/// @param operands 子节点，非空
public record Logical(LogicalOperator operator, List<Spec> operands) implements Spec {

    /// 紧凑构造器：校验操作数数量并复制为不可变列表。
    public Logical {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(operands, "operands");
        if (operands.isEmpty()) {
            throw new IllegalArgumentException("逻辑组合至少需要一个操作数");
        }
        if (operator == LogicalOperator.NOT && operands.size() != 1) {
            throw new IllegalArgumentException("NOT 只接受一个操作数");
        }
        operands = List.copyOf(operands);
    }

    /// 构造 AND 组合。
    ///
    /// @param specs 子节点，至少一个；为空时构造期抛 `IllegalArgumentException`
    /// @return 新的 `AND` 节点
    public static Logical and(Spec... specs) {
        return new Logical(LogicalOperator.AND, List.of(specs));
    }

    /// 构造 OR 组合。
    ///
    /// @param specs 子节点，至少一个；为空时构造期抛 `IllegalArgumentException`
    /// @return 新的 `OR` 节点
    public static Logical or(Spec... specs) {
        return new Logical(LogicalOperator.OR, List.of(specs));
    }

    /// 构造 NOT 组合。
    ///
    /// @param spec 子节点，非 `null`
    /// @return 新的 `NOT` 节点（恰好一个操作数）
    public static Logical not(Spec spec) {
        return new Logical(LogicalOperator.NOT, List.of(spec));
    }
}
