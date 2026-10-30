package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.ValueSelection;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 投影装配要能反射调用**别的包**里的包私有 DTO。
///
/// DTO 刻意放在 `io.github.nextentity.core.builder.projection` 子包，装配器与这个测试都在
/// `io.github.nextentity.core.builder`：同包时反射本来就能调 `setXxx`，`setAccessible(true)`
/// 有没有都一样，那条能力就没有证据。跨包调用包私有类的公开 setter 必须靠 `setAccessible`，
/// 否则 `Method#invoke` 抛 `IllegalAccessException`。
///
/// DTO 只能 `Class.forName` 取，测试里不编译期引用它（跨包包私有本来就引用不到）。
@DisplayName("select(Dto.class)：跨包的包私有 DTO 也能装配")
class CrossPackageDtoProjectionTest {

    /// 子包里的 DTO 只能按名字取：跨包的包私有类编译期引用不到，而把全类名写进源码会被 FQN 检查拦住。
    private static final String PACKAGE_PRIVATE_DTO =
            CrossPackageDtoProjectionTest.class.getPackageName() + ".projection.PackagePrivateDto";

    private final Recorder backend = new Recorder();

    @Test
    @DisplayName("包私有的 DTO 类在另一个包里照样装配成功（setter 需要 setAccessible）")
    void packagePrivateDtoInAnotherPackageAssembles() throws Exception {
        Class<?> type = Class.forName(PACKAGE_PRIVATE_DTO);
        EntityQuery<FakeEntity> query = new QueryBuilder<>(meta(), backend, backend);
        backend.rows = List.of((Object) new Object[] {"ann"});

        List<?> rows = select(query, type);

        assertThat(((ValueSelection) backend.last().selection()).items())
                .containsExactly(new PathProjection(AttributePath.of(FakeEntity.class, "name")));
        assertThat(rows).singleElement();
        Method name = type.getDeclaredMethod("name");
        name.setAccessible(true);
        assertThat(name.invoke(rows.getFirst())).isEqualTo("ann");
    }

    /// 按运行期拿到的 `Class<?>` 投影：泛型在这里只是把捕获类型递出来，不需要 raw 调用。
    private static <R> List<R> select(EntityQuery<FakeEntity> query, Class<R> type) {
        return query.select(type).list();
    }

    private static EntityType<FakeEntity> meta() {
        return new EntityType<>() {
            private final EntityAttribute id = new EntityAttribute() {
                @Override
                public String name() {
                    return "id";
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

            @Override
            public Class<FakeEntity> type() {
                return FakeEntity.class;
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

    /// 假后端：编译恒等（`S = QuerySpec`），执行只返回预置的行。
    private static final class Recorder implements Compiler<QuerySpec>, Executor<QuerySpec> {

        private final List<QuerySpec> specs = new ArrayList<>();
        private List<Object> rows = List.of();

        @Override
        public QuerySpec compile(QuerySpec spec) {
            return spec;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> List<R> list(QuerySpec query) {
            specs.add(query);
            return (List<R>) rows;
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

    /// 包内可见的实体类型，只用来给投影提供实体类型。
    @SuppressWarnings("unused")
    private static final class FakeEntity {}
}
