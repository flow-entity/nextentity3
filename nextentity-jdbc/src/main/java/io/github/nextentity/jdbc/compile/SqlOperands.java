package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.spec.AggregateOperand;
import io.github.nextentity.core.spec.ArithmeticOperand;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.BinaryOperator;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Operand;

/// 操作数渲染：路径/聚合/算术 → SQL 表达式；裸字面量 → `?` 并按顺序登记参数。
///
/// 需要类型转换的字面量（枚举列）由调用方先转再绑，这里只处理算术里的数值字面量。
final class SqlOperands {

    private final SqlRenderer renderer;

    SqlOperands(SqlRenderer renderer) {
        this.renderer = renderer;
    }

    /// 渲染操作数表达式。
    ///
    /// @param operand 操作数
    /// @return SQL 表达式片段
    String expression(Operand operand) {
        return switch (operand) {
            case AttributePath path -> renderer.paths().resolve(path).expression();
            case AggregateOperand aggregate -> aggregate(aggregate);
            case ArithmeticOperand arithmetic -> arithmetic(arithmetic);
            case Literal literal -> {
                renderer.addParameter(literal.value());
                yield "?";
            }
        };
    }

    /// 操作数的静态类型：算术取左操作数，聚合取函数结果类型。
    ///
    /// @param operand 操作数
    /// @return 静态类型
    Class<?> type(Operand operand) {
        return switch (operand) {
            case AttributePath path ->
                SqlPaths.columnType(renderer.paths().resolve(path).attribute());
            case AggregateOperand aggregate -> aggregateType(aggregate);
            case ArithmeticOperand arithmetic -> type(arithmetic.left());
            case Literal literal -> literal.value().getClass();
        };
    }

    private String aggregate(AggregateOperand aggregate) {
        String inner = renderer.paths().resolve(aggregate.path()).expression();
        return switch (aggregate.function()) {
            case MAX -> "max(" + inner + ")";
            case MIN -> "min(" + inner + ")";
            case AVG -> "avg(" + inner + ")";
            case SUM -> "sum(" + inner + ")";
            case COUNT -> "count(" + inner + ")";
            case COUNT_DISTINCT -> "count(distinct " + inner + ")";
        };
    }

    private Class<?> aggregateType(AggregateOperand aggregate) {
        return switch (aggregate.function()) {
            case COUNT, COUNT_DISTINCT -> Long.class;
            case AVG -> Double.class;
            case MAX, MIN, SUM ->
                SqlPaths.columnType(renderer.paths().resolve(aggregate.path()).attribute());
        };
    }

    private String arithmetic(ArithmeticOperand arithmetic) {
        return "(" + expression(arithmetic.left()) + " " + symbol(arithmetic.operator()) + " "
                + expression(arithmetic.right()) + ")";
    }

    private static String symbol(BinaryOperator operator) {
        return switch (operator) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case MOD -> "%";
        };
    }
}
