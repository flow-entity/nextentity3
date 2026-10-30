package io.github.nextentity.jdbc.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.EmbeddedProfile;
import io.github.nextentity.jdbc.support.IgnoredAssociationItem;
import io.github.nextentity.jdbc.support.MixedAssociationItem;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import java.util.List;
import org.junit.jupiter.api.Test;

/// `fetch(...)` 的 JDBC 契约：后端不组装实体图、没有懒加载代理，必须 fail-fast 拒绝并给出替代路径；
/// 静默忽略会让关联字段保持 `null` 而调用方以为预加载成功（设计 §7.1 CUT 表 / USAGE §8.1）。
class JdbcFetchRejectionTest {

    private final JdbcCompiler compiler = new JdbcCompiler(new JdbcMeta(), JdbcDialect.of("H2"));

    @Test
    void singleValuedFetchIsRejectedWithAlternative() {
        QuerySpec spec = spec(SnapshotAsset.class, AttributePath.of(SnapshotAsset.class, "owner"));

        Throwable failure = catchThrowable(() -> compiler.compile(spec));
        assertThat(failure)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("JDBC 后端不支持 fetch(预加载关联)")
                .hasMessageContaining(SnapshotAsset.class.getName())
                .hasMessageContaining("单值关联 [owner]")
                .hasMessageContaining("project(ProjectionMapper)")
                .hasMessageContaining("JPA");
        assertThat(failure.getMessage()).doesNotContain("集合关联");
    }

    @Test
    void collectionFetchIsRejectedWithAlternative() {
        QuerySpec spec = spec(IgnoredAssociationItem.class, AttributePath.of(IgnoredAssociationItem.class, "owners"));

        Throwable failure = catchThrowable(() -> compiler.compile(spec));
        assertThat(failure)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("JDBC 后端不支持 fetch(预加载关联)")
                .hasMessageContaining(IgnoredAssociationItem.class.getName())
                .hasMessageContaining("集合关联 [owners]")
                .hasMessageContaining("project(ProjectionMapper)");
        assertThat(failure.getMessage()).doesNotContain("单值关联");
        assertThat(failure.getMessage()).contains(IgnoredAssociationItem.class.getName() + " 集合关联 [owners]；");
    }

    @Test
    void fetchOfEmbeddedAttributeIsListedAsSingleValued() {
        QuerySpec spec = spec(EmbeddedProfile.class, AttributePath.of(EmbeddedProfile.class, "address"));

        assertThatThrownBy(() -> compiler.compile(spec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("单值关联 [address]");
    }

    @Test
    void fetchOfUnknownAttributeIsReportedRatherThanDropped() {
        QuerySpec spec = spec(SnapshotAsset.class, AttributePath.of(SnapshotAsset.class, "missing"));

        assertThatThrownBy(() -> compiler.compile(spec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("单值关联 [missing]");
    }

    @Test
    void specWithoutFetchIsCompiledNormally() {
        QuerySpec spec = new QuerySpec(
                SnapshotAsset.class,
                new EntitySelection(),
                null,
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null,
                List.of());

        assertThat(compiler.compile(spec).statement().sql()).startsWith("select t0.id, t0.name from asset");
    }

    @Test
    void mixedFetchListsBothAssociationShapes() {
        QuerySpec spec = new QuerySpec(
                MixedAssociationItem.class,
                new EntitySelection(),
                null,
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null,
                List.of(
                        AttributePath.of(MixedAssociationItem.class, "owner"),
                        AttributePath.of(MixedAssociationItem.class, "owners")));

        assertThatThrownBy(() -> compiler.compile(spec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("单值关联 [owner]、集合关联 [owners]");
    }

    private static QuerySpec spec(Class<?> type, AttributePath fetch) {
        return new QuerySpec(
                type, new EntitySelection(), null, List.of(), null, List.of(), Window.NONE, null, List.of(fetch));
    }
}
