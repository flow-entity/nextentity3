package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.QuerySpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `slice()` 的空页短路不许绕过后端的编译期拒绝。
///
/// `fetch(预加载关联)` 在 JDBC 上是**编译期** fail-fast（`JdbcCompiler#rejectFetch`）。列表规格若只在
/// 短路之后才编译，同一条公开调用是否抛异常就取决于表里有没有行：有行时抛，空表/offset 越界时静默
/// 返回空页。这里用「`fetch` 非空即抛」的假编译器把这条分叉钉住，与真库无关。
@DisplayName("QueryBuilder#slice 的编译期校验")
class QueryBuilderSliceCompileTest {

    private final Backend backend = new Backend();

    private EntityQuery<Item> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    /// 空表上带 `fetch` 的分页同样必须被后端拒绝：`total = 0` 只说明这一页没有行，
    /// 不说明这条规格合法。
    @Test
    @DisplayName("slice()：空表上带 fetch 仍抛后端拒绝，不静默返回空页")
    void sliceOnEmptyPageStillFailsFast() {
        backend.total = 0L;

        assertThatThrownBy(() -> query().fetch(Item::getOwner).slice(0, 10))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("JDBC 后端不支持 fetch");
    }

    /// 绿色对照：页内有行时本来就抛。两条用例一起说明「抛不抛」与数据无关。
    @Test
    @DisplayName("slice()：页内有行时带 fetch 抛同一个拒绝")
    void sliceWithRowsFailsFast() {
        backend.total = 1L;

        assertThatThrownBy(() -> query().fetch(Item::getOwner).slice(0, 10))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("JDBC 后端不支持 fetch");
    }

    /// 绿色对照：不带 `fetch` 的规格在空表上正常返回空页，且短路仍然不跑列表查询
    /// （编译一次不等于执行一次）。
    @Test
    @DisplayName("slice()：不带 fetch 的空表返回空页，且不执行列表查询")
    void sliceWithoutFetchOnEmptyPageReturnsEmptySlice() {
        backend.total = 0L;

        Slice<Item> slice = query().slice(5, 10);

        assertThat(slice.data()).isEmpty();
        assertThat(slice.total()).isZero();
        assertThat(slice.offset()).isEqualTo(5);
        assertThat(slice.limit()).isEqualTo(10);
        assertThat(backend.lists).as("短路路径只编译列表规格，不执行列表查询").isEmpty();
    }

    // ---------------------------------------------------------------- fixture

    private static EntityType<Item> meta() {
        return new EntityType<>() {
            private final EntityAttribute id = attribute("id", Long.class);

            @Override
            public Class<Item> type() {
                return Item.class;
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

    private static EntityAttribute attribute(String name, Class<?> type) {
        return new EntityAttribute() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Class<?> type() {
                return type;
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

    /// 假后端：编译期拒绝 `fetch`（模拟 `JdbcCompiler#rejectFetch`），执行只记账，`total` 可控。
    private static final class Backend implements Compiler<QuerySpec>, Executor<QuerySpec> {

        private final List<QuerySpec> lists = new ArrayList<>();
        private long total;

        @Override
        public QuerySpec compile(QuerySpec spec) {
            if (!spec.fetch().isEmpty()) {
                throw new UnsupportedOperationException(
                        "JDBC 后端不支持 fetch(预加载关联): " + spec.entityType().getName() + " " + spec.fetch());
            }
            return spec;
        }

        @Override
        public <R> List<R> list(QuerySpec query) {
            lists.add(query);
            return List.of();
        }

        @Override
        public long count(QuerySpec query) {
            return total;
        }

        @Override
        public boolean exists(QuerySpec query) {
            return false;
        }
    }

    /// 测试实体：只需要 getter 名，不需要任何 JPA 注解。
    @SuppressWarnings("unused")
    private static final class Item {

        private Long id;
        private Owner owner;

        public Long getId() {
            return id;
        }

        public Owner getOwner() {
            return owner;
        }

        private static final class Owner {

            private String name;

            public String getName() {
                return name;
            }
        }
    }
}
