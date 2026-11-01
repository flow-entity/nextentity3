package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.QuerySpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `fetch(...)` 只允许与实体行查询组合：投影查询的行不是实体，没有关联可填。
///
/// 契约：`FetchStep` 的 javadoc「不能在 `select(...)` 之后调（投影查询的行不是实体，没有关联可填）」。
/// 两个后端对这个组合的现状不一致：JPA 的 `JpaQueryBuilder#valueQuery` 根本不读 `spec.fetch()`（静默不预取，
/// 调用方以为预加载成功）；JDBC 的 `JdbcCompiler#rejectFetch` 直接 UOE。同一调用一方静默一方报错，
/// 所以判定落在 core 的 `QueryBuilder` 一处收口，文案与相邻的「锁只支持实体查询」同款。
///
/// 反向约束同样钉住：实体查询上的 `fetch(...)` 照常放行（别把正常用法打死）；`count()` / `exists()`
/// 不得被拒绝——`QuerySpec#forCounting` / `#forExistence` 会把 fetch 清掉，计数语句里没有预加载。
class QueryBuilderFetchOnProjectionSpecTest {

    private final Recorder backend = new Recorder();

    private EntityQuery<Row> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    @Test
    @DisplayName("投影 + fetch：list() 抛 UOE，文案与锁的收口同款")
    void fetchOnProjectionIsRejectedOnList() {
        assertThatThrownBy(() -> fetchOnProjection().list())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("fetch 只支持实体查询: " + Row.class.getName());
    }

    @Test
    @DisplayName("投影 + fetch：first() / single() 同一条取行漏斗，一样拒绝")
    void fetchOnProjectionIsRejectedOnEveryRowFunnel() {
        assertThatThrownBy(() -> fetchOnProjection().first())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("fetch 只支持实体查询: " + Row.class.getName());
        assertThatThrownBy(() -> fetchOnProjection().single())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("fetch 只支持实体查询: " + Row.class.getName());
    }

    @Test
    @DisplayName("投影 + fetch：slice() 在短路（total=0）之前就拒绝，不取决于表里有没有行")
    void fetchOnProjectionIsRejectedOnSliceBeforeShortCircuit() {
        assertThatThrownBy(() -> fetchOnProjection().slice(0, 10))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("fetch 只支持实体查询: " + Row.class.getName());
    }

    @Test
    @DisplayName("正向对照：实体查询上的 fetch(...) 照常放行（不是把 fetch 一律禁掉）")
    void fetchOnEntityQueryStillPasses() {
        query().fetch(Row::getStatus).list();

        assertThat(backend.last().selection()).isEqualTo(new EntitySelection());
        assertThat(backend.last().fetch()).containsExactly(AttributePath.of(Row.class, "status"));
    }

    @Test
    @DisplayName("count() / exists() 不受影响：fetch 在 forCounting / forExistence 里已被清掉")
    void fetchOnProjectionDoesNotBlockCountAndExists() {
        EntityQuery<Row> query = fetchOnProjection();

        assertThat(query.count()).isZero();
        assertThat(backend.last().fetch()).isEmpty();
        assertThat(query.exists()).isFalse();
        assertThat(backend.last().fetch()).isEmpty();
    }

    /// 投影查询 + fetch：`fetch(...)` 返回的是 [io.github.nextentity.api.BaseWhereStep]，没有 `select`，
    /// 所以只能在同一个 `EntityQuery` 上先取 fetch、再换投影（两个调用落在同一份规格里）。
    private EntityQuery<Row> fetchOnProjection() {
        EntityQuery<Row> query = query();
        query.fetch(Row::getStatus);
        query.select(Row::getName);
        return query;
    }

    private static EntityType<Row> meta() {
        return new EntityType<>() {
            private final EntityAttribute id = attribute("id");

            @Override
            public Class<Row> type() {
                return Row.class;
            }

            @Override
            public EntityAttribute id() {
                return id;
            }

            @Override
            public EntityAttribute version() {
                return null;
            }
        };
    }

    private static EntityAttribute attribute(String name) {
        return new EntityAttribute() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Class<?> type() {
                return Long.class;
            }

            @Override
            public Object get(Object entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void set(Object entity, Object value) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /// 测试实体：只需要 getter 名，不需要任何注解。
    @SuppressWarnings("unused")
    private static final class Row {

        private Long id;
        private String name;
        private String status;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getStatus() {
            return status;
        }
    }

    /// 假后端：编译恒等（`S = QuerySpec`），执行只记账。
    private static final class Recorder implements Compiler<QuerySpec>, Executor<QuerySpec> {

        private final List<QuerySpec> specs = new ArrayList<>();

        @Override
        public QuerySpec compile(QuerySpec spec) {
            return spec;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> List<R> list(QuerySpec query) {
            specs.add(query);
            return (List<R>) List.of();
        }

        @Override
        public long count(QuerySpec query) {
            specs.add(query);
            return 0;
        }

        @Override
        public boolean exists(QuerySpec query) {
            specs.add(query);
            return false;
        }

        QuerySpec last() {
            return specs.getLast();
        }
    }
}
