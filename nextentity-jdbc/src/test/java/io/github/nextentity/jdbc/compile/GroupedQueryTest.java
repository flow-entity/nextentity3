package io.github.nextentity.jdbc.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.dialect.SqlServerDialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.SnapshotItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/// `groupBy` 之后的 count / exists：组数走派生表，组存在性走 `group by … limit 1`。
class GroupedQueryTest {

    private static final JdbcMeta META = new JdbcMeta();

    private static QuerySpec grouped(Spec where) {
        return grouped(where, null);
    }

    private static QuerySpec grouped(Spec where, Spec having) {
        return new QuerySpec(
                SnapshotItem.class,
                new EntitySelection(),
                where,
                List.of(AttributePath.of(SnapshotItem.class, "status")),
                having,
                List.of(),
                Window.NONE,
                null);
    }

    private static Comparison scoreGreaterThan(int score) {
        return new Comparison(AttributePath.of(SnapshotItem.class, "score"), ComparisonOperator.GT, Literal.of(score));
    }

    @Test
    void groupCountWrapsGroupKeySelectInDerivedTable() {
        SqlStatement statement =
                new JdbcCompiler(META, new H2Dialect()).count(grouped(null).forGroupCount());

        assertThat(statement.sql())
                .isEqualTo("select count(*) from (select t0.status from item t0 group by t0.status) grouped");
        assertThat(statement.parameters()).isEmpty();
        assertThat(statement.columnTypes()).containsExactly(Long.class);
    }

    @Test
    void groupCountKeepsWhereBeforeGroupBy() {
        SqlStatement statement = new JdbcCompiler(META, new H2Dialect())
                .count(grouped(scoreGreaterThan(5)).forGroupCount());

        assertThat(statement.sql())
                .isEqualTo(
                        "select count(*) from (select t0.status from item t0 where t0.score > ? group by t0.status) grouped");
        assertThat(statement.parameters()).containsExactly(5);
        assertThat(statement.columnTypes()).containsExactly(Long.class);
    }

    @Test
    void groupExistsKeepsGroupByAndLimitsOneRow() {
        SqlStatement statement =
                new JdbcCompiler(META, new H2Dialect()).exists(grouped(null).forGroupExists());

        assertThat(statement.sql()).isEqualTo("select 1 from item t0 group by t0.status limit 1");
        assertThat(statement.columnTypes()).containsExactly(Boolean.class);
    }

    @Test
    void groupCountRendersHavingInsideDerivedTable() {
        SqlStatement statement = new JdbcCompiler(META, new H2Dialect())
                .count(grouped(scoreGreaterThan(5), Constant.TRUE).forGroupCount());

        assertThat(statement.sql())
                .isEqualTo("select count(*) from (select t0.status from item t0 where t0.score > ?"
                        + " group by t0.status having (1 = 1)) grouped");
        assertThat(statement.parameters()).containsExactly(5);
    }

    @Test
    void groupExistsRendersWhereAndHaving() {
        SqlStatement statement = new JdbcCompiler(META, new H2Dialect())
                .exists(grouped(scoreGreaterThan(5), Constant.TRUE).forGroupExists());

        assertThat(statement.sql())
                .isEqualTo("select 1 from item t0 where t0.score > ? group by t0.status having (1 = 1) limit 1");
        assertThat(statement.parameters()).containsExactly(5);
    }

    @Test
    void sqlServerGroupExistsUsesTopOne() {
        SqlStatement statement = new JdbcCompiler(META, new SqlServerDialect())
                .exists(grouped(null).forGroupExists());

        assertThat(statement.sql()).isEqualTo("select top (1) 1 from item t0 group by t0.status");
    }

    @Test
    void rowsCountOnGroupedSpecIsStillRejected() {
        assertThatThrownBy(() -> new JdbcCompiler(META, new H2Dialect())
                        .count(grouped(Constant.TRUE).forCount()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("分组/聚合规格的 count 未实现");
    }
}
