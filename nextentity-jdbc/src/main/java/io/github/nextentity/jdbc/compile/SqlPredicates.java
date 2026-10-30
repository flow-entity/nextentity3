package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.spec.AggregateOperand;
import io.github.nextentity.core.spec.ArithmeticOperand;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.Operand;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.StringFunctionOperand;
import io.github.nextentity.core.spec.SubstringOperand;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcTypes;
import java.util.ArrayList;
import java.util.List;

/// `Spec` 六节点的 SQL 渲染：穷举 switch，无 `default`。
///
/// 绑定值在渲染期按「另一侧操作数的静态类型」转换（枚举 → name/ordinal），
/// 因此 `parameters()` 里是 JDBC 可直接绑的值。
final class SqlPredicates {

    private static final String AND = " and ";

    private final SqlRenderer renderer;
    private final SqlOperands operands;

    SqlPredicates(SqlRenderer renderer) {
        this.renderer = renderer;
        this.operands = new SqlOperands(renderer);
    }

    /// 渲染一个谓词片段（不含 `where` / `having` 关键字）。
    ///
    /// @param spec 谓词节点
    /// @return SQL 片段
    String render(Spec spec) {
        return switch (spec) {
            case Comparison comparison -> comparison(comparison);
            case Between between -> between(between);
            case In in -> in(in);
            case NullCheck nullCheck -> nullCheck(nullCheck);
            case Logical logical -> logical(logical);
            case Constant constant -> constant.value() ? "1 = 1" : "1 = 0";
        };
    }

    private String comparison(Comparison comparison) {
        Operand right = comparison.right();
        if (comparison.operator() == ComparisonOperator.LIKE && !(right instanceof Literal)) {
            throw new IllegalArgumentException("LIKE 的右侧必须是字面量，不能是表达式");
        }
        if (right instanceof Literal literal) {
            String left = operands.expression(comparison.left());
            Object value = comparison.operator() == ComparisonOperator.LIKE
                    ? escapeBackslash(literal.value())
                    : literal.value();
            renderer.addParameter(convert(value, attributeOf(comparison.left())));
            String predicate = left + " " + operator(comparison.operator()) + " ?";
            return comparison.operator() == ComparisonOperator.LIKE
                    ? predicate + renderer.dialect().likeEscape()
                    : predicate;
        }
        if (comparison.left() instanceof Literal literal) {
            renderer.addParameter(convert(literal.value(), attributeOf(right)));
            // 走到这里时算子必不是 LIKE：上面已拒绝「LIKE 且右侧非字面量」，而右侧是字面量时已在上一个分支返回。
            return "? " + operator(comparison.operator()) + " " + operands.expression(right);
        }
        return operands.expression(comparison.left()) + " " + operator(comparison.operator()) + " "
                + operands.expression(right);
    }

    private String between(Between between) {
        JdbcEntityAttribute attribute = attributeOf(between.subject());
        return operands.expression(between.subject()) + " between " + bound(between.lower(), attribute) + AND
                + bound(between.upper(), attribute);
    }

    private String bound(Operand operand, JdbcEntityAttribute attribute) {
        if (operand instanceof Literal literal) {
            renderer.addParameter(convert(literal.value(), attribute));
            return "?";
        }
        return operands.expression(operand);
    }

    private String in(In in) {
        SqlPaths.Resolved resolved = renderer.paths().resolve(in.path());
        List<String> placeholders = new ArrayList<>();
        for (Object value : in.values()) {
            renderer.addParameter(convert(value, resolved.attribute()));
            placeholders.add("?");
        }
        String prefix = resolved.expression() + (in.negated() ? " not in (" : " in (");
        return prefix + String.join(", ", placeholders) + ")";
    }

    private String nullCheck(NullCheck nullCheck) {
        return operands.expression(nullCheck.path()) + (nullCheck.negated() ? " is not null" : " is null");
    }

    private String logical(Logical logical) {
        return switch (logical.operator()) {
            case AND -> "(" + join(logical.operands(), AND) + ")";
            case OR -> "(" + join(logical.operands(), " or ") + ")";
            case NOT -> "not (" + render(logical.operands().get(0)) + ")";
        };
    }

    private String join(List<Spec> specs, String separator) {
        List<String> rendered = new ArrayList<>();
        for (Spec spec : specs) {
            rendered.add(render(spec));
        }
        return String.join(separator, rendered);
    }

    private JdbcEntityAttribute attributeOf(Operand operand) {
        return switch (operand) {
            case AttributePath path -> renderer.paths().resolve(path).attribute();
            case AggregateOperand aggregate ->
                renderer.paths().resolve(aggregate.path()).attribute();
            case ArithmeticOperand arithmetic -> attributeOf(arithmetic.left());
            case StringFunctionOperand function -> attributeOf(function.subject());
            case SubstringOperand substring -> attributeOf(substring.subject());
            case Literal literal -> throw new IllegalStateException("字面量没有列类型: " + literal.value());
        };
    }

    /// LIKE 模式里的反斜杠翻倍后绑定：数据库把 `\` 当转义符，JPA 侧也把 `\` 翻倍
    /// （H2 `escape ''` / MySQL `replace(?, '\\', '\\\\')`），翻倍后才能按字面量匹配。
    /// `%`/`_` 保持通配语义不动。
    private static Object escapeBackslash(Object value) {
        return value instanceof String pattern ? pattern.replace("\\", "\\\\") : value;
    }

    /// 字面量绑定值：枚举 → name/ordinal，`java.time` / `BigInteger` → 驱动普遍接受的 `java.sql` 形态，
    /// 与写侧共用 [JdbcTypes#toLiteral(Object, JdbcEntityAttribute)]（谓词不套 `@Version` 的 0 初值）。
    private static Object convert(Object value, JdbcEntityAttribute attribute) {
        return JdbcTypes.toLiteral(value, attribute);
    }

    private static String operator(ComparisonOperator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NE -> "<>";
            case GT -> ">";
            case GE -> ">=";
            case LT -> "<";
            case LE -> "<=";
            case LIKE -> "like";
        };
    }
}
