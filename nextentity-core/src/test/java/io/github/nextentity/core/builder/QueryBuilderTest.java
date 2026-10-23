package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.Predicate;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AggregateFunction;
import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.core.spec.Window;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 查询构建器 → Spec 的结构断言：不连接数据库，用"编译器即恒等 + 执行器记账"的假后端。
///
/// 断言的是 AST 形状而不是 SQL，因此同一套用例对未来 JDBC 后端同样成立。
@DisplayName("QueryBuilder → Spec")
class QueryBuilderTest {

    private final Recorder backend = new Recorder();

    private EntityQuery<Order> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    // ------------------------------------------------------------------ where

    @Test
    @DisplayName("eq：单条件直接成为 WHERE，条件是实体字段路径")
    void eqBuildsComparison() {
        query().where(Order::getStatus).eq("CREATED").list();

        assertThat(backend.last().where())
                .isEqualTo(new Comparison(path("status"), ComparisonOperator.EQ, Literal.of("CREATED")));
        assertThat(backend.last().selection()).isEqualTo(new EntitySelection());
    }

    @Test
    @DisplayName("eqIfNotNull(null)：条件被跳过，WHERE 为空（不是永不匹配）")
    void eqIfNotNullSkipsNull() {
        query().where(Order::getStatus).eqIfNotNull(null).list();

        assertThat(backend.last().where()).isNull();
    }

    @Test
    @DisplayName("eqIfNotNull(值)：正常产生条件")
    void eqIfNotNullKeepsValue() {
        query().where(Order::getStatus).eqIfNotNull("PAID").list();

        assertThat(backend.last().where())
                .isEqualTo(new Comparison(path("status"), ComparisonOperator.EQ, Literal.of("PAID")));
    }

    @Test
    @DisplayName("多个 where 逐次 AND，且保持调用顺序")
    void conditionsAreAnded() {
        query().where(Order::getStatus).eq("A").where(Order::getOrgId).gt(1L).list();

        Logical where = (Logical) backend.last().where();
        assertThat(where.operator()).isEqualTo(LogicalOperator.AND);
        assertThat(where.operands())
                .containsExactly(
                        new Comparison(path("status"), ComparisonOperator.EQ, Literal.of("A")),
                        new Comparison(path("orgId"), ComparisonOperator.GT, Literal.of(1L)));
    }

    @Test
    @DisplayName("containsIfNotEmpty(\"\")：空串跳过条件")
    void containsIfNotEmptySkipsBlank() {
        query().where(Order::getName).containsIfNotEmpty("").list();

        assertThat(backend.last().where()).isNull();
    }

    @Test
    @DisplayName("contains：展开成 %v% 的 LIKE")
    void containsWrapsWildcards() {
        query().where(Order::getName).contains("abc").list();

        assertThat(backend.last().where())
                .isEqualTo(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("%abc%")));
    }

    @Test
    @DisplayName("likeIfNotNull：模式原样透传（不含通配符）")
    void likeKeepsPattern() {
        query().where(Order::getName).likeIfNotNull("a%").list();

        assertThat(backend.last().where())
                .isEqualTo(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("a%")));
    }

    @Test
    @DisplayName("in(空集合) → 恒假；notIn(空集合) → 恒真")
    void emptyMembershipBecomesConstant() {
        query().where(Order::getId).in(List.of()).list();
        assertThat(backend.last().where()).isEqualTo(Constant.FALSE);

        query().where(Order::getId).notIn(List.of()).list();
        assertThat(backend.last().where()).isNull();
    }

    @Test
    @DisplayName("in(非空集合)：值被复制进节点")
    void inKeepsValues() {
        query().where(Order::getId).in(List.of(1L, 2L)).list();

        assertThat(backend.last().where()).isEqualTo(new In(path("id"), List.of(1L, 2L), false));
    }

    @Test
    @DisplayName("isNull/isNotNull/between")
    void nullCheckAndBetween() {
        query().where(Order::getName).isNotNull().list();
        assertThat(backend.last().where()).isEqualTo(new NullCheck(path("name"), true));

        query().where(Order::getAge).between(1, 9).list();
        assertThat(backend.last().where()).isEqualTo(new Between(path("age"), Literal.of(1), Literal.of(9)));
    }

    @Test
    @DisplayName("where(Expression)：api 的 Op 值被解释成节点并 AND 进 WHERE")
    void expressionCondition() {
        Predicate<Order> left = Path.of(Order::getStatus).eq("A");
        Predicate<Order> right = Path.of(Order::getId).in(List.of(1L, 2L));
        query().where(left.or(right)).list();

        Logical where = (Logical) backend.last().where();
        assertThat(where.operator()).isEqualTo(LogicalOperator.OR);
        assertThat(where.operands()).hasSize(2);
    }

    @Test
    @DisplayName("where(Expression)：短路产生的恒真占位被忽略（条件不增加）")
    void alwaysTrueExpressionIgnored() {
        query().where(Path.of(Order::getStatus).eqIfNotNull(null)).list();

        assertThat(backend.last().where()).isNull();
    }

    @Test
    @DisplayName("or 分组：OR(AND(..), AND(..)) 不被拍平成四元 OR（point-mall TransactionRepository 的贷/借两组条件）")
    void orOfAndGroupsStayNested() {
        Predicate<Order> credit = Path.of(Order::getCreditId)
                .eq(1L)
                .and(Path.of(Order::getCreditType).eq("CUSTOMER"));
        Predicate<Order> debit = Path.of(Order::getDebitId)
                .eq(1L)
                .and(Path.of(Order::getDebitType).eq("CUSTOMER"));

        query().where(credit.or(debit)).list();

        Logical or = (Logical) backend.last().where();
        assertThat(or.operator()).isEqualTo(LogicalOperator.OR);
        assertThat(or.operands()).hasSize(2).allSatisfy(operand -> {
            assertThat(operand).isInstanceOf(Logical.class);
            assertThat(((Logical) operand).operator()).isEqualTo(LogicalOperator.AND);
            assertThat(((Logical) operand).operands()).hasSize(2);
        });
    }

    @Test
    @DisplayName("or 分组嵌在顶层 AND 里：外层的其它条件不被卷进 OR")
    void orGroupStaysInsideTopLevelAnd() {
        Predicate<Order> left = Path.of(Order::getCreditId).eq(1L);
        Predicate<Order> right = Path.of(Order::getDebitId).eq(1L);

        query().where(Order::getOrgId).eq(7L).where(left.or(right)).list();

        Logical and = (Logical) backend.last().where();
        assertThat(and.operator()).isEqualTo(LogicalOperator.AND);
        assertThat(and.operands()).hasSize(2);
        assertThat(and.operands().getFirst())
                .isEqualTo(new Comparison(path("orgId"), ComparisonOperator.EQ, Literal.of(7L)));
        assertThat(and.operands().get(1)).isInstanceOf(Logical.class);
        assertThat(((Logical) and.operands().get(1)).operator()).isEqualTo(LogicalOperator.OR);
    }

    // ----------------------------------------------------------------- select

    @Test
    @DisplayName("select(单列)：查询形态是单投影")
    void selectSingleColumn() {
        query().select(Order::getId).list();

        assertThat(backend.last().selection())
                .isEqualTo(new ValueSelection(List.of(new PathProjection(path("id"))), false));
    }

    @Test
    @DisplayName("select(聚合)：max 投影 + 分组")
    void selectAggregateWithGroupBy() {
        query().select(Path.of(Order::getOrgId), Path.of(Order::getAge).max())
                .groupBy(Order::getOrgId)
                .list();

        ValueSelection selection = (ValueSelection) backend.last().selection();
        assertThat(selection.items())
                .containsExactly(
                        new PathProjection(path("orgId")), new AggregateProjection(AggregateFunction.MAX, path("age")));
        assertThat(backend.last().groupBy()).containsExactly(path("orgId"));
        assertThat(backend.last().aggregate()).isTrue();
    }

    @Test
    @DisplayName("selectDistinct：带 distinct 标志")
    void selectDistinct() {
        query().selectDistinct(Order::getName).list();

        ValueSelection selection = (ValueSelection) backend.last().selection();
        assertThat(selection.distinct()).isTrue();
        assertThat(selection.items()).containsExactly(new PathProjection(path("name")));
    }

    @Test
    @DisplayName("双列投影：后端返回 Object[] 时装配成 Tuple2")
    void tupleAssembly() {
        backend.rows = List.of((Object) new Object[] {1L, 5});
        List<Tuple2<Long, Integer>> rows = query().select(
                        Path.of(Order::getId), Path.of(Order::getAge).max())
                .groupBy(Order::getId)
                .list();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get0()).isEqualTo(1L);
        assertThat(rows.getFirst().get1()).isEqualTo(5);
    }

    @Test
    @DisplayName("distinct().select(单列)：与 selectDistinct 等价")
    void distinctBeforeSingleColumnSelect() {
        query().distinct().select(Order::getName).list();

        ValueSelection selection = (ValueSelection) backend.last().selection();
        assertThat(selection.distinct()).isTrue();
        assertThat(selection.items()).containsExactly(new PathProjection(path("name")));
    }

    @Test
    @DisplayName("distinct().select(三列)：多列去重，列序保持")
    void distinctBeforeMultiColumnSelect() {
        query().distinct()
                .select(Path.of(Order::getId), Path.of(Order::getOrgId), Path.of(Order::getName))
                .list();

        ValueSelection selection = (ValueSelection) backend.last().selection();
        assertThat(selection.distinct()).isTrue();
        assertThat(selection.items())
                .containsExactly(
                        new PathProjection(path("id")),
                        new PathProjection(path("orgId")),
                        new PathProjection(path("name")));
    }

    @Test
    @DisplayName("distinct().project(映射器)：映射器路径同样去重")
    void distinctBeforeMapperProjection() {
        query().distinct()
                .project(new ProjectionMapper<Order, String>() {
                    @Override
                    public List<Expression<Order, ?>> expressions() {
                        return List.of(Path.of(Order::getName));
                    }

                    @Override
                    public String map(Tuple row) {
                        return (String) row.get(0);
                    }
                })
                .list();

        assertThat(((ValueSelection) backend.last().selection()).distinct()).isTrue();
    }

    @Test
    @DisplayName("distinct() 的旗标只被下一次投影消费：再次投影回到不去重")
    void distinctAppliesToNextProjectionOnly() {
        EntityQuery<Order> query = query();

        query.distinct().select(Order::getName).list();
        assertThat(((ValueSelection) backend.last().selection()).distinct()).isTrue();

        query.select(Order::getName).list();
        assertThat(((ValueSelection) backend.last().selection()).distinct()).isFalse();
    }

    @Test
    @DisplayName("未调用 distinct() 时投影不去重")
    void projectionIsNotDistinctByDefault() {
        query().select(Order::getName).list();

        assertThat(((ValueSelection) backend.last().selection()).distinct()).isFalse();
    }
    // ---------------------------------------------------------------- orderBy

    @Test
    @DisplayName("orderBy + desc：排序项按调用顺序累积")
    void orderByDesc() {
        query().orderBy(Order::getCreatedAt).desc().orderBy(Order::getId).asc().list();

        assertThat(backend.last().orderBy())
                .containsExactly(
                        new Ordering(path("createdAt"), Ordering.Direction.DESC),
                        new Ordering(path("id"), Ordering.Direction.ASC));
    }

    @Test
    @DisplayName("分页无 orderBy：自动补主键升序（2.2.3 autoAddIdOrder 默认开启）")
    void pagingAddsIdOrder() {
        query().list(10);

        assertThat(backend.last().window()).isEqualTo(new Window(0, 10));
        assertThat(backend.last().orderBy()).containsExactly(new Ordering(path("id"), Ordering.Direction.ASC));
    }

    @Test
    @DisplayName("list() 不限行：不自动补主键排序")
    void listWithoutLimitKeepsNoOrder() {
        query().list();

        assertThat(backend.last().window()).isEqualTo(Window.NONE);
        assertThat(backend.last().orderBy()).isEmpty();
    }

    @Test
    @DisplayName("已有 orderBy 的分页不再追加主键排序")
    void pagingKeepsExplicitOrder() {
        query().orderBy(Order::getCreatedAt).desc().list(5);

        assertThat(backend.last().orderBy()).containsExactly(new Ordering(path("createdAt"), Ordering.Direction.DESC));
    }

    @Test
    @DisplayName("聚合查询的分页不补主键排序")
    void aggregatePagingKeepsNoIdOrder() {
        query().select(Path.of(Order::getId), Path.of(Order::getAge).count())
                .groupBy(Order::getId)
                .list(5);

        assertThat(backend.last().orderBy()).isEmpty();
    }

    // ------------------------------------------------------------- terminate

    @Test
    @DisplayName("first()：list(0,1) 取首行")
    void first() {
        Order row = new Order();
        backend.rows = List.of(row);

        Order first = query().first();

        assertThat(first).isSameAs(row);
        assertThat(backend.last().window()).isEqualTo(new Window(0, 1));
    }

    @Test
    @DisplayName("single()：取 2 行以内，>1 行抛 IllegalStateException")
    void singleRejectsMultipleRows() {
        Order one = new Order();
        backend.rows = List.of(one);
        assertThat(query().single()).isSameAs(one);

        backend.rows = List.of(new Object(), new Object());
        assertThatThrownBy(() -> query().single())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("found more than one result, expected one");
        assertThat(backend.last().window()).isEqualTo(new Window(0, 2));
    }

    @Test
    @DisplayName("count()/exists()：清排序与窗口、去锁")
    void countAndExistsClearPagination() {
        backend.total = 7L;
        query().orderBy(Order::getId).asc().count();

        QuerySpec countSpec = backend.last();
        assertThat(backend.lastCount).isEqualTo(7L);
        assertThat(countSpec.orderBy()).isEmpty();
        assertThat(countSpec.window()).isEqualTo(Window.NONE);
        assertThat(countSpec.lock()).isNull();

        backend.exists = true;
        assertThat(query().exists()).isTrue();
        assertThat(backend.last().window()).isEqualTo(new Window(null, 1));
    }

    @Test
    @DisplayName("slice()：先 count 再取一页，total 进 Slice")
    void sliceRunsCountThenList() {
        backend.total = 25L;
        Order row = new Order();
        backend.rows = List.of(row);

        Slice<Order> slice = query().orderBy(Order::getId).asc().slice(10, 5);

        assertThat(slice.total()).isEqualTo(25L);
        assertThat(slice.data()).containsExactly(row);
        assertThat(backend.specs).hasSize(2);
        assertThat(backend.specs.get(1).window()).isEqualTo(new Window(10, 5));
    }

    @Test
    @DisplayName("slice()：offset 已越过总数时不再跑列表查询")
    void sliceShortCircuitsWhenOffsetBeyondTotal() {
        backend.total = 3L;

        Slice<Order> slice = query().slice(10, 5);

        assertThat(slice.data()).isEmpty();
        assertThat(slice.total()).isEqualTo(3L);
        assertThat(backend.specs).hasSize(1);
    }

    @Test
    @DisplayName("方法名 → 属性名：get/is 前缀、单字符、连续大写")
    void fieldNameRules() {
        assertThat(PathRefs.fieldName("getStatus")).isEqualTo("status");
        assertThat(PathRefs.fieldName("isActive")).isEqualTo("active");
        assertThat(PathRefs.fieldName("getId")).isEqualTo("id");
        assertThat(PathRefs.fieldName("getURL")).isEqualTo("URL");
        assertThat(PathRefs.fieldName("status")).isEqualTo("status");
    }

    @Test
    @DisplayName("嵌套路径：EntityPath.of(A::getB).get(C::getD) 解析成两段属性名")
    void nestedPathTwoSegments() {
        EntityPath<Order, Order.Customer> customer = EntityPath.of(Order::getCustomer);

        assertThat(customer.refs()).hasSize(1);
        assertThat(customer.get(Order.Customer::getOrgId).refs()).hasSize(2);

        query().where(customer.get(Order.Customer::getOrgId)).eq(9L).list();

        assertThat(backend.last().where())
                .isEqualTo(new Comparison(
                        AttributePath.of(Order.class, "customer", "orgId"), ComparisonOperator.EQ, Literal.of(9L)));
    }

    @Test
    @DisplayName("嵌套路径：三段 customer.get(org).get(name) 保留段顺序")
    void nestedPathThreeSegments() {
        StringPath<Order> name =
                EntityPath.of(Order::getCustomer).get(Order.Customer::getOrg).get(Order.Organization::getName);

        assertThat(name.refs()).hasSize(3);

        query().where(name).containsIfNotEmpty("abc").list();

        assertThat(backend.last().where())
                .isEqualTo(new Comparison(
                        AttributePath.of(Order.class, "customer", "org", "name"),
                        ComparisonOperator.LIKE,
                        Literal.of("%abc%")));
    }

    @Test
    @DisplayName("非方法引用也非 Path 的 PathRef：报出类型与原因，而不是 NoSuchMethodException")
    void foreignPathRefRejectedWithDiagnosis() {
        PathRef<Order, Long> handWritten = new PathRef<>() {
            @Override
            public Long apply(Order entity) {
                return entity.getId();
            }
        };

        assertThatThrownBy(() -> query().where(handWritten).eq(1L).list())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是方法引用")
                .hasMessageContaining("EntityPath.of(A::getB).get(C::getD)");
    }
    // ---------------------------------------------------------------- fixture

    private static AttributePath path(String name) {
        return AttributePath.of(Order.class, name);
    }

    private static EntityType<Order> meta() {
        return new EntityType<>() {
            private final EntityAttribute id = attribute("id", Long.class);

            @Override
            public Class<Order> type() {
                return Order.class;
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

    /// 假后端：编译恒等（`S = QuerySpec`），执行只记账。
    private static final class Recorder implements Compiler<QuerySpec>, Executor<QuerySpec> {

        private final List<QuerySpec> specs = new ArrayList<>();
        private List<Object> rows = List.of();
        private long total;
        private long lastCount;
        private boolean exists;

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
            lastCount = total;
            return total;
        }

        @Override
        public boolean exists(QuerySpec query) {
            specs.add(query);
            return exists;
        }

        QuerySpec last() {
            return specs.getLast();
        }
    }

    /// 测试实体：只需要 getter 名，不需要任何 JPA 注解。
    @SuppressWarnings("unused")
    private static final class Order {

        private Long id;
        private Long orgId;
        private String name;
        private String status;
        private Integer age;
        private Instant createdAt;
        private Long creditId;
        private Long debitId;
        private String creditType;
        private String debitType;

        @SuppressWarnings("unused")
        private static final class Customer {

            private Long orgId;
            private Organization org;

            public Long getOrgId() {
                return orgId;
            }

            public Organization getOrg() {
                return org;
            }
        }

        @SuppressWarnings("unused")
        private static final class Organization {

            private String name;

            public String getName() {
                return name;
            }
        }

        public Long getId() {
            return id;
        }

        public Long getOrgId() {
            return orgId;
        }

        public String getName() {
            return name;
        }

        public String getStatus() {
            return status;
        }

        public Integer getAge() {
            return age;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public Long getCreditId() {
            return creditId;
        }

        public Long getDebitId() {
            return debitId;
        }

        public String getCreditType() {
            return creditType;
        }

        public String getDebitType() {
            return debitType;
        }

        public Customer getCustomer() {
            return null;
        }
    }
}
