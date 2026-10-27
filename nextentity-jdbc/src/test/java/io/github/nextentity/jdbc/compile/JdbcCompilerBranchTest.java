package io.github.nextentity.jdbc.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.spec.AggregateFunction;
import io.github.nextentity.core.spec.AggregateOperand;
import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.ArithmeticOperand;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.BinaryOperator;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.ComputedProjection;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.Operand;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.RowMapper;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.IgnoredAssociationItem;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.MultiAssociationItem;
import io.github.nextentity.jdbc.support.OrdinalItem;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 逐分支覆盖：字面量在左 / 表达式右值 / Constant / 枚举两种存储 / 路径与 join 缓存 / 投影三型 /
/// 窗口与锁的非常规形态 / 拒绝分支文案。直接构造 `QuerySpec`，绕开 `QueryBuilder` 的合法性收敛。
class JdbcCompilerBranchTest {

    private static final RowMapperIdentity ROW_MAPPER = new RowMapperIdentity();

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new H2Dialect());
    private final CapturingExecutor executor = new CapturingExecutor();

    private QueryBuilder<SnapshotItem, JdbcQuery> items() {
        return new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor);
    }

    private static AttributePath path(Class<?> type, String... names) {
        return new AttributePath(type, List.of(names));
    }

    private static QuerySpec entity(Class<?> type, SpecWhere where, List<Ordering> orderBy, LockMode lock) {
        return new QuerySpec(
                type,
                new EntitySelection(),
                where == null ? null : where.value(),
                List.of(),
                null,
                orderBy,
                Window.NONE,
                lock);
    }

    private static QuerySpec entity(Class<?> type, Spec where) {
        return new QuerySpec(type, new EntitySelection(), where, List.of(), null, List.of(), Window.NONE, null);
    }

    private static QuerySpec values(List<Projection> items, boolean distinct, Spec where) {
        return new QuerySpec(
                SnapshotItem.class,
                new ValueSelection(items, distinct, ROW_MAPPER),
                where,
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null);
    }

    private static Comparison comparison(Operand left, ComparisonOperator operator, Operand right) {
        return new Comparison(left, operator, right);
    }

    private SqlStatement select(QuerySpec spec) {
        return compiler.compile(spec).statement();
    }

    // ---------------------------------------------------------------- Constant

    @Test
    void constantTrueRendersOneEqualsOne() {
        SqlStatement statement = select(entity(SnapshotItem.class, Constant.TRUE));

        assertThat(statement.sql()).endsWith("where 1 = 1");
        assertThat(statement.parameters()).isEmpty();
    }

    @Test
    void constantFalseRendersOneEqualsZero() {
        SqlStatement statement = select(entity(SnapshotItem.class, Constant.FALSE));

        assertThat(statement.sql()).endsWith("where 1 = 0");
        assertThat(statement.parameters()).isEmpty();
    }

    // ---------------------------------------------------------------- 比较两侧形态

    @Test
    void literalOnTheLeftIsBoundBeforeTheColumnExpression() {
        SqlStatement statement = select(entity(
                SnapshotItem.class,
                comparison(Literal.of(ItemStatus.ACTIVE), ComparisonOperator.EQ, path(SnapshotItem.class, "status"))));

        assertThat(statement.sql()).endsWith("where ? = t0.status");
        assertThat(statement.parameters()).containsExactly("ACTIVE");
    }

    @Test
    void literalOnTheLeftConvertsByOrdinalStorage() {
        SqlStatement statement = select(entity(
                OrdinalItem.class,
                comparison(Literal.of(ItemStatus.DONE), ComparisonOperator.EQ, path(OrdinalItem.class, "status"))));

        assertThat(statement.sql()).endsWith("where ? = t0.status");
        assertThat(statement.parameters()).containsExactly(2);
    }

    @Test
    void likeRejectsExpressionOnTheRight() {
        QuerySpec spec = entity(
                SnapshotItem.class,
                comparison(
                        path(SnapshotItem.class, "name"), ComparisonOperator.LIKE, path(SnapshotItem.class, "name")));

        assertThatThrownBy(() -> select(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LIKE 的右侧必须是字面量，不能是表达式");
    }

    @Test
    void enumLiteralConvertsToNameWhenColumnStoresNames() {
        SqlStatement statement = select(entity(
                SnapshotItem.class,
                comparison(path(SnapshotItem.class, "status"), ComparisonOperator.EQ, Literal.of(ItemStatus.NEW))));

        assertThat(statement.parameters()).containsExactly("NEW");
    }

    @Test
    void enumLiteralConvertsToOrdinalWhenColumnStoresOrdinals() {
        SqlStatement statement = select(entity(
                OrdinalItem.class,
                comparison(path(OrdinalItem.class, "status"), ComparisonOperator.EQ, Literal.of(ItemStatus.DONE))));

        assertThat(statement.parameters()).containsExactly(2);
    }

    @Test
    void inListConvertsEveryEnumElement() {
        SqlStatement statement = select(entity(
                SnapshotItem.class,
                new In(path(SnapshotItem.class, "status"), List.of(ItemStatus.NEW, ItemStatus.DONE), false)));

        assertThat(statement.sql()).endsWith("where t0.status in (?, ?)");
        assertThat(statement.parameters()).containsExactly("NEW", "DONE");
    }

    @Test
    void inListConvertsOrdinalElements() {
        SqlStatement statement = select(entity(
                OrdinalItem.class,
                new In(path(OrdinalItem.class, "status"), List.of(ItemStatus.NEW, ItemStatus.ACTIVE), false)));

        assertThat(statement.parameters()).containsExactly(0, 1);
    }

    // ---------------------------------------------------------------- 算术与聚合操作数

    @Test
    void arithmeticOperandInComparisonIsParenthesizedAndBindsItsLiteral() {
        QuerySpec spec = entity(
                SnapshotItem.class,
                comparison(
                        new ArithmeticOperand(BinaryOperator.ADD, path(SnapshotItem.class, "score"), Literal.of(1)),
                        ComparisonOperator.GT,
                        Literal.of(5)));

        SqlStatement statement = select(spec);

        assertThat(statement.sql()).endsWith("where (t0.score + ?) > ?");
        assertThat(statement.parameters()).containsExactly(1, 5);
    }

    @Test
    void aggregateOperandOnTheLeftConvertsByTheAggregatedColumn() {
        QuerySpec spec = entity(
                SnapshotItem.class,
                comparison(
                        new AggregateOperand(AggregateFunction.MAX, path(SnapshotItem.class, "score")),
                        ComparisonOperator.GT,
                        Literal.of(5)));

        SqlStatement statement = select(spec);

        assertThat(statement.sql()).endsWith("where max(t0.score) > ?");
        assertThat(statement.parameters()).containsExactly(5);
    }

    @Test
    void comparisonOfTwoLiteralsIsRejectedBecauseNoColumnTypeExists() {
        QuerySpec spec = entity(SnapshotItem.class, comparison(Literal.of(1), ComparisonOperator.EQ, Literal.of(2)));

        assertThatThrownBy(() -> select(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("字面量没有列类型: 1");
    }

    // ---------------------------------------------------------------- BETWEEN

    @Test
    void betweenBoundsMayBeExpressionsAndBindInOrder() {
        QuerySpec spec = entity(
                SnapshotItem.class,
                new Between(
                        path(SnapshotItem.class, "score"),
                        new ArithmeticOperand(BinaryOperator.ADD, path(SnapshotItem.class, "score"), Literal.of(1)),
                        new ArithmeticOperand(BinaryOperator.MUL, path(SnapshotItem.class, "score"), Literal.of(2))));

        SqlStatement statement = select(spec);

        assertThat(statement.sql()).endsWith("where t0.score between (t0.score + ?) and (t0.score * ?)");
        assertThat(statement.parameters()).containsExactly(1, 2);
    }

    @Test
    void betweenLiteralBoundsConvertBySubjectStorage() {
        QuerySpec spec = entity(
                OrdinalItem.class,
                new Between(
                        path(OrdinalItem.class, "status"), Literal.of(ItemStatus.NEW), Literal.of(ItemStatus.DONE)));

        SqlStatement statement = select(spec);

        assertThat(statement.parameters()).containsExactly(0, 2);
    }

    // ---------------------------------------------------------------- 逻辑组合

    @Test
    void notWithSingleOperandAndNestedLogicalBothRender() {
        QuerySpec spec = entity(
                SnapshotItem.class,
                new Logical(
                        LogicalOperator.NOT,
                        List.of(new Logical(
                                LogicalOperator.OR,
                                List.of(new NullCheck(path(SnapshotItem.class, "note"), true), Constant.TRUE)))));

        SqlStatement statement = select(spec);

        assertThat(statement.sql()).endsWith("where not ((t0.note is not null or 1 = 1))");
    }

    // ---------------------------------------------------------------- 路径与 join

    @Test
    void unknownRootAttributeIsRejectedWithQualifiedName() {
        QuerySpec spec = entity(
                SnapshotItem.class, comparison(path(SnapshotItem.class, "nope"), ComparisonOperator.EQ, Literal.of(1)));

        assertThatThrownBy(() -> select(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知属性: " + SnapshotItem.class.getName() + ".nope");
    }

    @Test
    void nonAssociationSegmentInTheMiddleIsRejectedAsUnknown() {
        QuerySpec spec = entity(
                SnapshotItem.class,
                comparison(path(SnapshotItem.class, "score", "x"), ComparisonOperator.EQ, Literal.of(1)));

        assertThatThrownBy(() -> select(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知属性: " + SnapshotItem.class.getName() + ".score");
    }

    @Test
    void collectionAssociationPathIsRejected() {
        QuerySpec spec = entity(
                IgnoredAssociationItem.class,
                comparison(path(IgnoredAssociationItem.class, "owners"), ComparisonOperator.EQ, Literal.of(1)));

        assertThatThrownBy(() -> select(spec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("集合关联路径未支持: " + IgnoredAssociationItem.class.getName() + ".owners");
    }

    @Test
    void associationAsProjectionIsRejected() {
        QuerySpec spec = new QuerySpec(
                SnapshotAsset.class,
                new ValueSelection(List.of(new PathProjection(path(SnapshotAsset.class, "owner"))), false, ROW_MAPPER),
                null,
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null);

        assertThatThrownBy(() -> select(spec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("投影关联对象未支持: " + SnapshotAsset.class.getName() + ".owner");
    }

    @Test
    void repeatedAssociationPrefixUsesOneJoinAndItsAlias() {
        AttributePath ownerName = path(SnapshotAsset.class, "owner", "name");
        QuerySpec spec = new QuerySpec(
                SnapshotAsset.class,
                new EntitySelection(),
                comparison(ownerName, ComparisonOperator.EQ, Literal.of("a")),
                List.of(),
                null,
                List.of(new Ordering(ownerName, Ordering.Direction.ASC)),
                Window.NONE,
                null);

        SqlStatement statement = select(spec);

        assertThat(statement.sql())
                .isEqualTo("select t0.id, t0.name from asset t0"
                        + " left join owner t1 on t1.id = t0.owner_id"
                        + " where t1.name = ? order by t1.name asc");
    }

    @Test
    void distinctAssociationsRegisterDistinctJoinsWithSequentialAliases() {
        AttributePath ownerName = path(MultiAssociationItem.class, "owner", "name");
        AttributePath creatorName = path(MultiAssociationItem.class, "creator", "name");
        QuerySpec spec = new QuerySpec(
                MultiAssociationItem.class,
                new EntitySelection(),
                new Logical(
                        LogicalOperator.AND,
                        List.of(
                                comparison(ownerName, ComparisonOperator.EQ, Literal.of("a")),
                                comparison(creatorName, ComparisonOperator.EQ, Literal.of("b")))),
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null);

        SqlStatement statement = select(spec);

        assertThat(statement.sql())
                .isEqualTo("select t0.id from multi_association_item t0"
                        + " left join owner t1 on t1.id = t0.owner_id"
                        + " left join owner t2 on t2.id = t0.creator_id"
                        + " where (t1.name = ? and t2.name = ?)");
        assertThat(statement.parameters()).containsExactly("a", "b");
    }

    // ---------------------------------------------------------------- 投影与列类型

    @Test
    void entitySelectionRegistersColumnTypePerColumnIncludingEnumStorage() {
        SqlStatement statement = select(entity(SnapshotItem.class, (Spec) null));

        assertThat(statement.columnTypes())
                .containsExactly(Long.class, String.class, Integer.class, Integer.class, String.class, String.class);
    }

    @Test
    void ordinalEntityColumnTypeIsInteger() {
        SqlStatement statement = select(entity(OrdinalItem.class, (Spec) null));

        assertThat(statement.sql()).isEqualTo("select t0.id, t0.status from ordinal_item t0");
        assertThat(statement.columnTypes()).containsExactly(Long.class, Integer.class);
    }

    @Test
    void computedProjectionsCoverLiteralPathAggregateAndArithmeticTypes() {
        SqlStatement literal = select(values(List.of(new ComputedProjection(Literal.of(10))), false, null));
        assertThat(literal.sql()).isEqualTo("select ? from item t0");
        assertThat(literal.parameters()).containsExactly(10);
        assertThat(literal.columnTypes()).containsExactly(Integer.class);

        SqlStatement attribute =
                select(values(List.of(new ComputedProjection(path(SnapshotItem.class, "score"))), false, null));
        assertThat(attribute.sql()).isEqualTo("select t0.score from item t0");
        assertThat(attribute.columnTypes()).containsExactly(Integer.class);

        SqlStatement aggregate = select(values(
                List.of(new ComputedProjection(
                        new AggregateOperand(AggregateFunction.MAX, path(SnapshotItem.class, "score")))),
                false,
                null));
        assertThat(aggregate.sql()).isEqualTo("select max(t0.score) from item t0");
        assertThat(aggregate.columnTypes()).containsExactly(Integer.class);

        SqlStatement arithmetic = select(values(
                List.of(new ComputedProjection(
                        new ArithmeticOperand(BinaryOperator.ADD, path(SnapshotItem.class, "score"), Literal.of(1)))),
                false,
                null));
        assertThat(arithmetic.sql()).isEqualTo("select (t0.score + ?) from item t0");
        assertThat(arithmetic.parameters()).containsExactly(1);
        assertThat(arithmetic.columnTypes()).containsExactly(Integer.class);
    }

    @Test
    void aggregateProjectionsRegisterResultColumnTypes() {
        List<Projection> items = List.of(
                new AggregateProjection(AggregateFunction.COUNT, path(SnapshotItem.class, "id")),
                new AggregateProjection(AggregateFunction.COUNT_DISTINCT, path(SnapshotItem.class, "id")),
                new AggregateProjection(AggregateFunction.AVG, path(SnapshotItem.class, "score")),
                new AggregateProjection(AggregateFunction.MAX, path(SnapshotItem.class, "score")),
                new AggregateProjection(AggregateFunction.MIN, path(SnapshotItem.class, "score")),
                new AggregateProjection(AggregateFunction.SUM, path(SnapshotItem.class, "score")));

        SqlStatement statement = select(values(items, false, null));

        assertThat(statement.sql())
                .isEqualTo("select count(t0.id), count(distinct t0.id), avg(t0.score), max(t0.score),"
                        + " min(t0.score), sum(t0.score) from item t0");
        assertThat(statement.columnTypes())
                .containsExactly(Long.class, Long.class, Double.class, Integer.class, Integer.class, Integer.class);
    }

    @Test
    void computedProjectionOnOrdinalColumnUsesIntegerType() {
        QuerySpec spec = new QuerySpec(
                OrdinalItem.class,
                new ValueSelection(
                        List.of(new ComputedProjection(path(OrdinalItem.class, "status"))), false, ROW_MAPPER),
                null,
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null);

        assertThat(select(spec).columnTypes()).containsExactly(Integer.class);
    }

    // ---------------------------------------------------------------- 表达式 vs 字面量的类型来源

    @Test
    void computedAggregateTypesCoverCountAvgAndSum() {
        SqlStatement count = select(values(
                List.of(new ComputedProjection(
                        new AggregateOperand(AggregateFunction.COUNT, path(SnapshotItem.class, "score")))),
                false,
                null));
        assertThat(count.columnTypes()).containsExactly(Long.class);

        SqlStatement avg = select(values(
                List.of(new ComputedProjection(
                        new AggregateOperand(AggregateFunction.AVG, path(SnapshotItem.class, "score")))),
                false,
                null));
        assertThat(avg.columnTypes()).containsExactly(Double.class);

        SqlStatement sum = select(values(
                List.of(new ComputedProjection(
                        new AggregateOperand(AggregateFunction.SUM, path(SnapshotItem.class, "score")))),
                false,
                null));
        assertThat(sum.columnTypes()).containsExactly(Integer.class);
    }

    @Test
    void pathProjectionOfEnumColumnUsesStorageType() {
        SqlStatement statement =
                select(values(List.of(new PathProjection(path(SnapshotItem.class, "status"))), false, null));

        assertThat(statement.sql()).isEqualTo("select t0.status from item t0");
        assertThat(statement.columnTypes()).containsExactly(String.class);
    }

    @Test
    void comparisonWithBackslashInNonLikeValueIsNotEscaped() {
        SqlStatement statement = select(entity(
                SnapshotItem.class,
                comparison(path(SnapshotItem.class, "name"), ComparisonOperator.EQ, Literal.of("a\\b"))));

        assertThat(statement.parameters()).containsExactly("a\\b");
    }

    @Test
    void likeWithNonStringLiteralBindsValueUnchanged() {
        SqlStatement statement = select(entity(
                SnapshotItem.class,
                comparison(path(SnapshotItem.class, "score"), ComparisonOperator.LIKE, Literal.of(5))));

        assertThat(statement.parameters()).containsExactly(5);
    }

    @Test
    void aggregateOperandWithEnumLiteralConvertsByAggregatedColumn() {
        SqlStatement statement = select(entity(
                SnapshotItem.class,
                comparison(
                        new AggregateOperand(AggregateFunction.MAX, path(SnapshotItem.class, "status")),
                        ComparisonOperator.EQ,
                        Literal.of(ItemStatus.NEW))));

        assertThat(statement.parameters()).containsExactly("NEW");
    }

    @Test
    void arithmeticOperandAttributeComesFromLeftmostPath() {
        QuerySpec spec = entity(
                SnapshotItem.class,
                comparison(
                        new ArithmeticOperand(BinaryOperator.ADD, path(SnapshotItem.class, "status"), Literal.of(1)),
                        ComparisonOperator.EQ,
                        Literal.of(ItemStatus.NEW)));

        assertThat(select(spec).parameters()).containsExactly(1, "NEW");
    }

    // ---------------------------------------------------------------- LIKE 反斜杠口径

    @Test
    void containsDoublesBackslashesBeforeBinding() {
        items().where(SnapshotItem::name).contains("p\\l").list();

        assertThat(executor.last.sql()).endsWith("where t0.name like ?");
        assertThat(executor.last.parameters()).containsExactly("%p\\\\l%");
    }

    @Test
    void likePatternDoublesBackslashesBeforeBinding() {
        items().where(SnapshotItem::name).like("%p\\l%").list();

        assertThat(executor.last.sql()).endsWith("where t0.name like ?");
        assertThat(executor.last.parameters()).containsExactly("%p\\\\l%");
    }

    @Test
    void wildcardsInLikeValuesAreLeftUnescaped() {
        items().where(SnapshotItem::name).contains("p_l").list();
        assertThat(executor.last.parameters()).containsExactly("%p_l%");

        items().where(SnapshotItem::name).contains("a%").list();
        assertThat(executor.last.parameters()).containsExactly("%a%%");
    }

    // ---------------------------------------------------------------- 窗口与锁

    @Test
    void nullWindowIsTreatedAsNoClause() {
        SqlRenderer renderer = new SqlRenderer(meta, entity(SnapshotItem.class, (Spec) null));

        assertThat(new SqlClauses(renderer).window(null)).isEmpty();
    }

    @Test
    void nonPessimisticWriteLockIsRejected() {
        QuerySpec spec = entity(SnapshotItem.class, null, List.of(), LockMode.PESSIMISTIC_READ);

        assertThatThrownBy(() -> select(spec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("JDBC 后端只支持 PESSIMISTIC_WRITE: PESSIMISTIC_READ");
    }

    @Test
    void pessimisticWriteOnValueSelectionIsRejected() {
        QuerySpec spec = new QuerySpec(
                SnapshotItem.class,
                new ValueSelection(List.of(new PathProjection(path(SnapshotItem.class, "name"))), false, ROW_MAPPER),
                null,
                List.of(),
                null,
                List.of(),
                Window.NONE,
                LockMode.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> select(spec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("锁只支持实体查询: " + SnapshotItem.class.getName());
    }

    // ---------------------------------------------------------------- count / exists

    @Test
    void countOfNonDistinctValueSelectionUsesCountStar() {
        SqlStatement statement =
                compiler.count(values(List.of(new PathProjection(path(SnapshotItem.class, "name"))), false, null));

        assertThat(statement.sql()).isEqualTo("select count(*) from item t0");
        assertThat(statement.columnTypes()).containsExactly(Long.class);
    }

    @Test
    void countOfMultiColumnDistinctIsRejectedWithColumnCountAndEntity() {
        QuerySpec spec = values(
                List.of(
                        new PathProjection(path(SnapshotItem.class, "name")),
                        new PathProjection(path(SnapshotItem.class, "score"))),
                true,
                null);

        assertThatThrownBy(() -> compiler.count(spec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("多列 distinct 规格的 count 未实现（列数 2，方言语义不一致）：" + SnapshotItem.class.getName());
    }

    @Test
    void countOfAggregateSpecIsRejected() {
        QuerySpec spec = new QuerySpec(
                SnapshotItem.class,
                new ValueSelection(
                        List.of(new AggregateProjection(AggregateFunction.COUNT, path(SnapshotItem.class, "id"))),
                        false,
                        ROW_MAPPER),
                null,
                List.of(path(SnapshotItem.class, "status")),
                null,
                List.of(),
                Window.NONE,
                null);

        assertThatThrownBy(() -> compiler.count(spec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("分组/聚合规格的 count 未实现（2.2.3 走子查询计数：" + SnapshotItem.class.getName() + "）");
    }

    @Test
    void existsOfAggregateSpecIsRejected() {
        QuerySpec spec = new QuerySpec(
                SnapshotItem.class,
                new EntitySelection(),
                null,
                List.of(),
                Constant.TRUE,
                List.of(),
                Window.NONE,
                null);

        assertThatThrownBy(() -> compiler.exists(spec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("分组/聚合规格的 exists 未实现：" + SnapshotItem.class.getName());
    }

    // ---------------------------------------------------------------- 构造期校验

    @Test
    void compilerRejectsNullArguments() {
        assertThatThrownBy(() -> new JdbcCompiler(null, new H2Dialect()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("meta");
        assertThatThrownBy(() -> new JdbcCompiler(meta, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dialect");
        assertThatThrownBy(() -> compiler.compile(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("spec");
        assertThatThrownBy(() -> compiler.count(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("spec");
        assertThatThrownBy(() -> compiler.exists(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("spec");
    }

    @Test
    void selectionAndStatementRecordsRejectNulls() {
        SqlStatement statement = new SqlStatement("select 1", List.of(), List.of());

        assertThatThrownBy(() -> new SqlStatement(null, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("sql");
        assertThatThrownBy(() -> new SqlStatement("select 1", null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("parameters");
        assertThatThrownBy(() -> new SqlStatement("select 1", List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("columnTypes");
        assertThatThrownBy(() -> new JdbcQuery(null, statement))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("spec");
        assertThatThrownBy(() -> new JdbcQuery(entity(SnapshotItem.class, (Spec) null), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("statement");
    }

    // ---------------------------------------------------------------- 合成 null 检查（内部 API 直接调）

    @Test
    void predicatesRejectNullSpecViaSwitchNullCheck() {
        SqlRenderer renderer = new SqlRenderer(meta, entity(SnapshotItem.class, (Spec) null));

        assertThatThrownBy(() -> new SqlPredicates(renderer).render(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void operandsRejectNullOperandViaSwitchNullCheck() {
        SqlRenderer renderer = new SqlRenderer(meta, entity(SnapshotItem.class, (Spec) null));
        SqlOperands operands = new SqlOperands(renderer);

        assertThatThrownBy(() -> operands.expression(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> operands.type(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void projectionsRejectNullProjectionViaSwitchNullCheck() {
        SqlRenderer renderer = new SqlRenderer(meta, entity(SnapshotItem.class, (Spec) null));

        assertThatThrownBy(() -> new SqlProjections(renderer).expression(null))
                .isInstanceOf(NullPointerException.class);
    }

    /// 捕获式执行器：`QueryBuilder` 只把编译产物交给它，不连库。
    private static final class CapturingExecutor implements Executor<JdbcQuery> {

        private JdbcQuery last;

        @Override
        public <R> List<R> list(JdbcQuery query) {
            last = query;
            return List.of();
        }

        @Override
        public long count(JdbcQuery query) {
            last = query;
            return 0L;
        }

        @Override
        public boolean exists(JdbcQuery query) {
            last = query;
            return false;
        }
    }

    /// 值查询只需要一个装配器占位：本类断言的都是编译产物，不看结果行。
    private static final class RowMapperIdentity implements RowMapper<Object> {

        @Override
        public Object map(Tuple row) {
            return row;
        }
    }

    /// 便于在 `entity(...)` 里传「无 where」而不与 `Selection` 参数混淆的小包装。
    private record SpecWhere(Spec value) {}
}
