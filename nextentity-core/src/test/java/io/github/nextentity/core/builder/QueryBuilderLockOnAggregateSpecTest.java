package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.LockMode;
import io.github.nextentity.api.Path;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.QuerySpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `lock(...)` 只允许加在「实体行」查询上：`groupBy` / `having`（即 `QuerySpec#aggregate()`）之后
/// 行不是实体行，必须 fail-fast。
///
/// 契约：`docs/DESIGN.md` §7.1 CUT-20「锁只支持实体查询：值选择/聚合规格带锁 → UOE」与
/// `docs/USAGE.md` §9「值选择（`select(...)`）或聚合规格上调用 `lock(...)` 抛
/// `UnsupportedOperationException`」——两处都没有「仅 JPA」/「仅 JDBC」的限定。
///
/// 两个后端的实测后果不同但都违反契约：JDBC 渲染出 `... group by ... for update`（H2/PostgreSQL
/// 直接语法错、MySQL 接受 → 同一调用一方炸一方静默持锁）；JPA 侧 Hibernate 不报错，而是悄悄换成
/// follow-on locking（分组查询之后多发 N 条 `select ... for update`）。所以判定落在 core 的
/// `QueryBuilder`：一处收口两后端，且校验点在唯一能同时看到 `lock` 与 `groupBy`/`having` 的地方
/// （`lock()` 本身看不到分组，`groupBy()` 本身看不到锁）。
///
/// 反向约束同样钉住：`count()` / `exists()` 会把锁清掉（`QuerySpec#forCount` / `#forExists`），
/// 因此同一条链上它们**不得**被拒绝——否则「聚合规格一律不许加锁」会被实现成「聚合规格不许出现
/// 在 lock 之后」，把合法的计数用法一起打死。
class QueryBuilderLockOnAggregateSpecTest {

    private final Recorder backend = new Recorder();

    private EntityQuery<Row> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    @Test
    @DisplayName("groupBy(...) 之后 lock(...)：list() 抛 UOE，文案与后端既有拒绝逐字一致")
    void lockAfterGroupByIsRejectedOnList() {
        assertThatThrownBy(() -> query().groupBy(Row::getStatus)
                        .lock(LockMode.PESSIMISTIC_WRITE)
                        .list())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("锁只支持实体查询: " + Row.class.getName());
    }

    @Test
    @DisplayName("having(...)（无 groupBy 的聚合规格）之后 lock(...)：list() 同样抛 UOE")
    void lockAfterHavingIsRejectedOnList() {
        assertThatThrownBy(() -> query().having(Path.of(Row::getId).count().gt(0L))
                        .lock(LockMode.PESSIMISTIC_READ)
                        .list())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("锁只支持实体查询: " + Row.class.getName());
    }

    @Test
    @DisplayName("聚合规格 + 锁在取行入口一律拒绝：list() / first() / single() 同一条漏斗")
    void lockAfterGroupByIsRejectedOnEveryRowFunnel() {
        assertThatThrownBy(() -> query().groupBy(Row::getStatus)
                        .lock(LockMode.PESSIMISTIC_WRITE)
                        .list(5))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("锁只支持实体查询: " + Row.class.getName());
        assertThatThrownBy(() -> query().groupBy(Row::getStatus)
                        .lock(LockMode.PESSIMISTIC_WRITE)
                        .first())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("锁只支持实体查询: " + Row.class.getName());
        assertThatThrownBy(() -> query().groupBy(Row::getStatus)
                        .lock(LockMode.PESSIMISTIC_WRITE)
                        .single())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("锁只支持实体查询: " + Row.class.getName());
    }

    @Test
    @DisplayName("count() / exists() 不受影响：锁在 forCounting / forExistence 里已被清掉")
    void lockAfterGroupByDoesNotBlockCountAndExists() {
        long groups =
                query().groupBy(Row::getStatus).lock(LockMode.PESSIMISTIC_WRITE).count();
        boolean any =
                query().groupBy(Row::getStatus).lock(LockMode.PESSIMISTIC_WRITE).exists();

        assertThat(groups).isZero();
        assertThat(any).isFalse();
        assertThat(backend.specs()).hasSize(2).allSatisfy(spec -> assertThat(spec.lock())
                .as("计数/存在性规格里锁必须已被清掉，否则后端会渲染出 group by + for update")
                .isNull());
    }

    @Test
    @DisplayName("正向对照：实体查询上的锁照常放行（不是把锁一律禁掉）")
    void lockOnPlainEntityQueryStillPasses() {
        query().where(Row::getStatus)
                .eq("PAID")
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list();

        assertThat(backend.last().lock()).isEqualTo(LockMode.PESSIMISTIC_WRITE);
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
        private String status;

        public Long getId() {
            return id;
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

        List<QuerySpec> specs() {
            return specs;
        }

        QuerySpec last() {
            return specs.getLast();
        }
    }
}
