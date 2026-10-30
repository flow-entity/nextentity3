package io.github.nextentity.jdbc.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.core.spec.Assignment;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.ComputedProjection;
import io.github.nextentity.core.spec.DeleteSpec;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.RowMapper;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.UpdateSpec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.ConvertedItem;
import io.github.nextentity.jdbc.support.EmbeddedAddress;
import io.github.nextentity.jdbc.support.EmbeddedProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

/// `@Embedded` 的路径解析：查询侧（扁平列 + `嵌入属性.字段` 两段）与条件改删的扁平模式。
///
/// 嵌入对象本身不是列，整体投影必须明确拒绝；嵌入列也只有两段路径，再下钻没有对应的 SQL。
class EmbeddedPathTest {

    private static final RowMapperIdentity ROW_MAPPER = new RowMapperIdentity();

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new H2Dialect());
    private final JdbcEntityType<EmbeddedProfile> type = meta.entityType(EmbeddedProfile.class);

    @Test
    void entitySelectionListsFlattenedEmbeddedColumnsInDeclarationOrder() {
        SqlStatement statement = compiler.compile(entity(null, List.of())).statement();

        assertThat(statement.sql())
                .isEqualTo("select t0.id, t0.town, t0.address_street, t0.label from embedded_profile t0");
        assertThat(statement.columnTypes()).containsExactly(Long.class, String.class, String.class, String.class);
    }

    @Test
    void convertedColumnUsesTheConverterDatabaseTypeForRowReading() {
        QuerySpec spec = new QuerySpec(
                ConvertedItem.class, new EntitySelection(), null, List.of(), null, List.of(), Window.NONE, null);

        SqlStatement statement = compiler.compile(spec).statement();

        assertThat(statement.sql()).isEqualTo("select t0.id, t0.status, t0.note from converted_item t0");
        assertThat(statement.columnTypes()).containsExactly(Long.class, String.class, String.class);
    }

    @Test
    void whereOnEmbeddedColumnResolvesToTheFlattenedColumn() {
        SqlStatement statement = compiler.compile(entity(
                        new Comparison(path("address", "city"), ComparisonOperator.EQ, Literal.of("Yantai")),
                        List.of()))
                .statement();

        assertThat(statement.sql())
                .isEqualTo("select t0.id, t0.town, t0.address_street, t0.label from embedded_profile t0"
                        + " where t0.town = ?");
        assertThat(statement.parameters()).containsExactly("Yantai");
    }

    @Test
    void orderByEmbeddedColumnResolvesToTheFlattenedColumn() {
        SqlStatement statement = compiler.compile(
                        entity(null, List.of(new Ordering(path("address", "street"), Ordering.Direction.ASC))))
                .statement();

        assertThat(statement.sql())
                .isEqualTo("select t0.id, t0.town, t0.address_street, t0.label from embedded_profile t0"
                        + " order by t0.address_street asc");
    }

    @Test
    void groupByEmbeddedColumnResolvesToTheFlattenedColumn() {
        QuerySpec spec = new QuerySpec(
                EmbeddedProfile.class,
                new EntitySelection(),
                null,
                List.of(path("address", "street")),
                null,
                List.of(),
                Window.NONE,
                null);

        assertThat(compiler.compile(spec).statement().sql())
                .isEqualTo("select t0.id, t0.town, t0.address_street, t0.label from embedded_profile t0"
                        + " group by t0.address_street");
    }

    @Test
    void projectingTheEmbeddedObjectItselfIsRejected() {
        assertThatThrownBy(() -> compiler.compile(values(new ComputedProjection(path("address")))))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("嵌入对象不能整体投影: " + EmbeddedProfile.class.getName() + ".address");
    }

    @Test
    void drillingDeeperThanOneEmbeddedLevelIsRejected() {
        assertThatThrownBy(() -> compiler.compile(values(new ComputedProjection(path("address", "city", "x")))))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("嵌入属性不支持再下钻: " + EmbeddedProfile.class.getName() + ".address.city.x");
    }

    @Test
    void unknownEmbeddedColumnIsRejectedWithTheEmbeddableTypeInTheMessage() {
        assertThatThrownBy(() -> compiler.compile(values(new ComputedProjection(path("address", "zip")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知属性: " + EmbeddedAddress.class.getName() + ".zip");
    }

    // ---------------------------------------------------------------- 条件改删（扁平模式）

    @Test
    void updateOnEmbeddedColumnRendersWithoutTableAlias() {
        SqlStatement statement = ConditionalSql.update(
                new UpdateSpec(EmbeddedProfile.class, List.of(new Assignment(path("address", "city"), "Yantai")), null),
                type,
                new H2Dialect());

        assertThat(statement.sql()).isEqualTo("update embedded_profile set town=?");
        assertThat(statement.parameters()).containsExactly("Yantai");
    }

    @Test
    void deleteWithEmbeddedColumnConditionRendersWithoutTableAlias() {
        SqlStatement statement = ConditionalSql.delete(
                new DeleteSpec(
                        EmbeddedProfile.class,
                        new Comparison(path("address", "city"), ComparisonOperator.EQ, Literal.of("Yantai"))),
                type,
                new H2Dialect());

        assertThat(statement.sql()).isEqualTo("delete from embedded_profile where town = ?");
        assertThat(statement.parameters()).containsExactly("Yantai");
    }

    @Test
    void updateRejectsTheEmbeddedObjectItself() {
        assertThatThrownBy(() -> ConditionalSql.update(
                        new UpdateSpec(EmbeddedProfile.class, List.of(new Assignment(path("address"), "x")), null),
                        type,
                        new H2Dialect()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件改删只支持嵌入字段列: " + EmbeddedProfile.class.getName() + ".address");
    }

    @Test
    void updateRejectsExtraPathSegmentsBelowAScalarColumn() {
        assertThatThrownBy(() -> ConditionalSql.update(
                        new UpdateSpec(EmbeddedProfile.class, List.of(new Assignment(path("label", "x"), "x")), null),
                        type,
                        new H2Dialect()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件改删不支持关联路径: " + EmbeddedProfile.class.getName() + ".label.x");
    }

    @Test
    void deleteRejectsUnknownAttribute() {
        assertThatThrownBy(() -> ConditionalSql.delete(
                        new DeleteSpec(
                                EmbeddedProfile.class,
                                new Comparison(path("missing"), ComparisonOperator.EQ, Literal.of(1))),
                        type,
                        new H2Dialect()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知属性: " + EmbeddedProfile.class.getName() + ".missing");
    }

    @Test
    void deleteRejectsUnknownEmbeddedColumn() {
        assertThatThrownBy(() -> ConditionalSql.delete(
                        new DeleteSpec(
                                EmbeddedProfile.class,
                                new Comparison(path("address", "zip"), ComparisonOperator.EQ, Literal.of("x"))),
                        type,
                        new H2Dialect()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知属性: " + EmbeddedAddress.class.getName() + ".zip");
    }

    private static AttributePath path(String... names) {
        return new AttributePath(EmbeddedProfile.class, List.of(names));
    }

    private static QuerySpec values(Projection... items) {
        return new QuerySpec(
                EmbeddedProfile.class,
                new ValueSelection(List.of(items), false, ROW_MAPPER),
                null,
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null);
    }

    private static QuerySpec entity(Spec where, List<Ordering> orderBy) {
        return new QuerySpec(
                EmbeddedProfile.class, new EntitySelection(), where, List.of(), null, orderBy, Window.NONE, null);
    }

    /// 值查询只需要一个装配器占位：本类断言编译产物，不看结果行。
    private static final class RowMapperIdentity implements RowMapper<Object> {

        @Override
        public Object map(Tuple row) {
            return row;
        }
    }
}
