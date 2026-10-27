package io.github.nextentity.tests.jdbc.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.tests.jdbc.ItemEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 评审发现 R2：设计 §3.0 冻结「exists 的 columnTypes = [Boolean]（占位）」，现状返回空表。
///
/// 复跑：`bash tmp/scripts/mvnl.sh -pl nextentity-tests-jdbc -am test -Dtest=ReviewR2ExistsColumnTypesTest`
class ReviewR2ExistsColumnTypesTest {

    @Test
    void existsDeclaresBooleanPlaceholderColumnType() {
        JdbcCompiler compiler = new JdbcCompiler(new JdbcMeta(), JdbcDialect.of("H2"));
        QuerySpec spec = new QuerySpec(
                ItemEntity.class, new EntitySelection(), null, List.of(), null, List.of(), Window.NONE, null);

        assertThat(compiler.exists(spec).columnTypes())
                .as("设计 §3.0 冻结 exists columnTypes=[Boolean]（执行器不读列，但契约/快照要钉住）")
                .containsExactly(Boolean.class);
    }

    @Test
    void countDeclaresLongColumnType() {
        JdbcCompiler compiler = new JdbcCompiler(new JdbcMeta(), JdbcDialect.of("H2"));
        QuerySpec spec = new QuerySpec(
                ItemEntity.class, new EntitySelection(), null, List.of(), null, List.of(), Window.NONE, null);

        assertThat(compiler.count(spec).columnTypes()).containsExactly(Long.class);
    }
}
