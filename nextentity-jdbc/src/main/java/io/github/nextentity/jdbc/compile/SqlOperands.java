package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.spec.AggregateOperand;
import io.github.nextentity.core.spec.ArithmeticOperand;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.BinaryOperator;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Operand;
import io.github.nextentity.core.spec.StringFunctionOperand;
import io.github.nextentity.core.spec.SubstringOperand;

/// 操作数渲染：路径/聚合/算术/字符串函数/截取 → SQL 表达式；裸字面量 → `?` 并按顺序登记参数。
///
/// 需要类型转换的字面量（枚举列 / `java.time` 列）由调用方先转再绑，这里只处理算术里的数值字面量。
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
            case StringFunctionOperand function -> stringFunction(function);
            case SubstringOperand substring -> substring(substring);
            case Literal literal -> {
                renderer.addParameter(literal.value());
                yield "?";
            }
        };
    }

    /// 操作数的静态类型：算术取左操作数，聚合取函数结果类型，字符串函数按函数定，
    /// 截取结果恒为 `String`（设计 §7.2 DEC-15 静态类型收窄）。
    ///
    /// @param operand 操作数
    /// @return 静态类型
    Class<?> type(Operand operand) {
        return switch (operand) {
            case AttributePath path ->
                SqlPaths.columnType(renderer.paths().resolve(path).attribute());
            case AggregateOperand aggregate -> aggregateType(aggregate);
            case ArithmeticOperand arithmetic -> type(arithmetic.left());
            case StringFunctionOperand function ->
                switch (function.function()) {
                    case LENGTH -> Integer.class;
                    case LOWER, UPPER, TRIM -> String.class;
                };
            case SubstringOperand substring -> String.class;
            case Literal literal -> literal.value().getClass();
        };
    }

    private String aggregate(AggregateOperand aggregate) {
        String inner = renderer.paths().resolve(aggregate.path()).expression();
        return switch (aggregate.function()) {
            case MAX -> "max(" + inner + ")";
            case MIN -> "min(" + inner + ")";
            case AVG -> renderer.dialect().averageOf(inner);
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

    /// 字符串函数：LOWER / UPPER / TRIM 恒同形；LENGTH 的 SQL 名称按方言（SQL Server 是 `len`）。
    private String stringFunction(StringFunctionOperand function) {
        String subject = expression(function.subject());
        return switch (function.function()) {
            case LOWER -> "lower(" + subject + ")";
            case UPPER -> "upper(" + subject + ")";
            case TRIM -> "trim(" + subject + ")";
            case LENGTH -> renderer.dialect().lengthOf(subject);
        };
    }

    /// 截取：API 的 `offset` 从 0 起算，SQL 的起始位置从 1 起算；`length == null` 表示到结尾，
    /// 由方言决定「无长度」形态（默认两参 `substring(x, pos)`，SQL Server 三参上界）。
    private String substring(SubstringOperand substring) {
        String subject = expression(substring.subject());
        return renderer.dialect().substring(subject, substring.offset() + 1, substring.length());
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
