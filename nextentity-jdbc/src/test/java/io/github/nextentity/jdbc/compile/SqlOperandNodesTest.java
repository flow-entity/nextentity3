package io.github.nextentity.jdbc.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.ComputedProjection;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Operand;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.RowMapper;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.StringFunction;
import io.github.nextentity.core.spec.StringFunctionOperand;
import io.github.nextentity.core.spec.SubstringOperand;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.dialect.SqlServerDialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.SnapshotItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 3.1.0 新增的字符串节点（`StringFunctionOperand` / `SubstringOperand`）的渲染与类型收窄。
///
/// 位置参数一律转 1 起算（`substring(x, offset)` 的 `offset` 从 0 起算）；省略长度走方言「无长度」形态，
/// 不把 `Integer.MAX_VALUE >> 1` 当通用字面量下发。
class SqlOperandNodesTest {

    private static final RowMapperIdentity ROW_MAPPER = new RowMapperIdentity();

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcCompiler h2 = new JdbcCompiler(meta, new H2Dialect());
    private final JdbcCompiler sqlServer = new JdbcCompiler(meta, new SqlServerDialect());

    @Test
    void lowerUpperAndTrimRenderLowercaseFunctionNames() {
        assertThat(computed(h2, function(StringFunction.LOWER, path("name"))).sql())
                .isEqualTo("select lower(t0.name) from item t0");
        assertThat(computed(h2, function(StringFunction.UPPER, path("name"))).sql())
                .isEqualTo("select upper(t0.name) from item t0");
        assertThat(computed(h2, function(StringFunction.TRIM, path("name"))).sql())
                .isEqualTo("select trim(t0.name) from item t0");
    }

    @Test
    void lengthGoesThroughTheDialectHookAndYieldsInteger() {
        SqlStatement statement = computed(h2, function(StringFunction.LENGTH, path("name")));

        assertThat(statement.sql()).isEqualTo("select length(t0.name) from item t0");
        assertThat(statement.columnTypes()).containsExactly(Integer.class);
    }

    @Test
    void lengthOnSqlServerUsesLen() {
        SqlStatement statement = computed(sqlServer, function(StringFunction.LENGTH, path("name")));

        assertThat(statement.sql()).isEqualTo("select len(t0.name) from item t0");
    }

    @Test
    void nestedStringFunctionsRenderInsideOut() {
        Operand nested = function(StringFunction.LOWER, function(StringFunction.TRIM, path("name")));

        SqlStatement statement = computed(h2, nested);

        assertThat(statement.sql()).isEqualTo("select lower(trim(t0.name)) from item t0");
        assertThat(statement.columnTypes()).containsExactly(String.class);
    }

    @Test
    void substringWithLengthIsRenderedOneBased() {
        SqlStatement statement = computed(h2, new SubstringOperand(path("name"), 2, 3));

        assertThat(statement.sql()).isEqualTo("select substring(t0.name, 3, 3) from item t0");
        assertThat(statement.columnTypes()).containsExactly(String.class);
    }

    @Test
    void substringWithoutLengthUsesTheDialectShortForm() {
        SqlStatement statement = computed(h2, new SubstringOperand(path("name"), 2, null));

        assertThat(statement.sql()).isEqualTo("select substring(t0.name, 3) from item t0");
    }

    @Test
    void sqlServerSubstringWithoutLengthUsesTheIntUpperBoundInsteadOfTheSentinel() {
        SqlStatement statement = computed(sqlServer, new SubstringOperand(path("name"), 2, null));

        assertThat(statement.sql()).isEqualTo("select substring(t0.name, 3, 2147483647) from item t0");
    }

    @Test
    void predicateOnLowerOperandBindsTheSubjectColumnLiteral() {
        QuerySpec spec = entity(
                comparison(function(StringFunction.LOWER, path("name")), ComparisonOperator.EQ, Literal.of("ABC")));

        SqlStatement statement = h2.compile(spec).statement();

        assertThat(statement.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status"
                        + " from item t0 where lower(t0.name) = ?");
        assertThat(statement.parameters()).containsExactly("ABC");
    }

    @Test
    void predicateOnSubstringOperandBindsTheSubjectColumnLiteral() {
        QuerySpec spec = entity(
                comparison(new SubstringOperand(path("name"), 1, null), ComparisonOperator.EQ, Literal.of("ab")));

        SqlStatement statement = h2.compile(spec).statement();

        assertThat(statement.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status"
                        + " from item t0 where substring(t0.name, 2) = ?");
        assertThat(statement.parameters()).containsExactly("ab");
    }

    @Test
    void predicateOnLengthOperandComparesTheSubjectColumnLiteral() {
        QuerySpec spec =
                entity(comparison(function(StringFunction.LENGTH, path("name")), ComparisonOperator.GT, Literal.of(5)));

        SqlStatement statement = h2.compile(spec).statement();

        assertThat(statement.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status"
                        + " from item t0 where length(t0.name) > ?");
        assertThat(statement.parameters()).containsExactly(5);
    }

    private SqlStatement computed(JdbcCompiler compiler, Operand operand) {
        return compiler.compile(values(List.of(new ComputedProjection(operand))))
                .statement();
    }

    private static AttributePath path(String name) {
        return new AttributePath(SnapshotItem.class, List.of(name));
    }

    private static StringFunctionOperand function(StringFunction function, Operand subject) {
        return new StringFunctionOperand(function, subject);
    }

    private static Comparison comparison(Operand left, ComparisonOperator operator, Operand right) {
        return new Comparison(left, operator, right);
    }

    private static QuerySpec values(List<Projection> items) {
        return new QuerySpec(
                SnapshotItem.class,
                new ValueSelection(items, false, ROW_MAPPER),
                null,
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null);
    }

    private static QuerySpec entity(Spec where) {
        return new QuerySpec(
                SnapshotItem.class, new EntitySelection(), where, List.of(), null, List.of(), Window.NONE, null);
    }

    /// 值查询只需要一个装配器占位：本类断言编译产物，不看结果行。
    private static final class RowMapperIdentity implements RowMapper<Object> {

        @Override
        public Object map(Tuple row) {
            return row;
        }
    }
}
