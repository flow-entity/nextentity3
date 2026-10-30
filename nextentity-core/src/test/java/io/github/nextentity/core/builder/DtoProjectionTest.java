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
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.ValueSelection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `select(Dto.class)`：列集来自 DTO 属性名，行按 record 构造器 / setter 装配。
///
/// 断言分两层：列的**形状**（哪些实体属性、什么顺序、去重旗标）与整行的**装配结果**（[RowAssembler]
/// 端到端跑一遍，不 mock 反射）。失败路径全部钉住（接口、空 record、无 setter、无无参构造器、
/// 类型不匹配、实体类 + distinct），因为这些拒绝是本能力的边界，不能靠"没测到"掩盖。
@DisplayName("QueryBuilder select(Dto.class)")
class DtoProjectionTest {

    private final Recorder backend = new Recorder();

    private EntityQuery<Order> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    // ------------------------------------------------------------- record DTO

    @Test
    @DisplayName("record：每个组件一列，按组件名匹配实体属性，列序即组件序")
    void recordSelectsComponentsInOrder() {
        query().select(NameAndAge.class).list();

        ValueSelection selection = selection();
        assertThat(selection.items())
                .containsExactly(new PathProjection(path("name")), new PathProjection(path("age")));
        assertThat(selection.distinct()).isFalse();
    }

    @Test
    @DisplayName("record：行按规范构造器装配成 record 实例")
    void recordAssemblesRows() {
        query().select(NameAndAge.class).list();

        assertThat(assemble(new Object[] {"ann", 3})).containsExactly(new NameAndAge("ann", 3));
    }

    @Test
    @DisplayName("record：列值类型与组件类型不匹配时报装配失败，而不是抛反射的原始错误")
    void recordTypeMismatchFailsLoudly() {
        query().select(SwappedTypes.class).list();

        assertThatThrownBy(() -> assemble(new Object[] {"ann", 3}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DTO 投影装配失败")
                .hasMessageContaining("SwappedTypes")
                .hasMessageContaining("不匹配");
    }

    @Test
    @DisplayName("record 无组件：拒绝，而不是投影出零列")
    void emptyRecordRejected() {
        assertThatThrownBy(() -> query().select(Empty.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可映射的属性")
                .hasMessageContaining("Empty");
    }

    // --------------------------------------------------------------- bean DTO

    @Test
    @DisplayName("普通类：属性名来自 setXxx，列序按属性名排序（反射方法序不保证稳定）")
    void beanSelectsSortedSetters() {
        query().select(BeanDto.class).list();

        assertThat(selection().items())
                .containsExactly(new PathProjection(path("age")), new PathProjection(path("name")));
    }

    @Test
    @DisplayName("普通类：行先无参构造再逐列走 setter")
    void beanAssemblesRows() {
        query().select(BeanDto.class).list();

        List<Object> rows = assemble(new Object[] {3, "ann"});
        assertThat(rows).singleElement().isInstanceOf(BeanDto.class);
        assertThat(((BeanDto) rows.getFirst()).name()).isEqualTo("ann");
        assertThat(((BeanDto) rows.getFirst()).age()).isEqualTo(3);
    }

    @Test
    @DisplayName("普通类：setURL 的属性名保持 URL（JavaBeans 口径），不被降成 uRL")
    void beanKeepsAllCapsPropertyName() {
        query().select(UrlDto.class).list();

        assertThat(selection().items()).containsExactly(new PathProjection(path("URL")));
    }

    @Test
    @DisplayName("普通类无 setter：拒绝")
    void beanWithoutSettersRejected() {
        assertThatThrownBy(() -> query().select(GetterOnly.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可映射的属性")
                .hasMessageContaining("GetterOnly");
    }

    @Test
    @DisplayName("普通类无无参构造器：拒绝")
    void beanWithoutNoArgConstructorRejected() {
        assertThatThrownBy(() -> query().select(NoDefaultConstructor.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无参构造器")
                .hasMessageContaining("NoDefaultConstructor");
    }

    @Test
    @DisplayName("setter 参数类型不匹配：装配失败，消息点名 setter")
    void beanSetterTypeMismatchFailsLoudly() {
        query().select(SwapBeanDto.class).list();

        assertThatThrownBy(() -> assemble(new Object[] {3, "ann"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SwapBeanDto")
                .hasMessageContaining("setAge");
    }

    // --------------------------------------------------------------- 去重/实体

    @Test
    @DisplayName("distinct().select(Dto.class)：DTO 投影带上 SELECT DISTINCT（selectDistinct 的写法）")
    void distinctAppliesToDtoProjection() {
        query().distinct().select(NameAndAge.class).list();

        assertThat(selection().distinct()).isTrue();
    }

    @Test
    @DisplayName("select(实体类)：退化为整实体查询，行透传不装配")
    void entityTypeFallsBackToEntitySelection() {
        Order order = new Order();
        backend.rows = List.of(order);

        List<Order> rows = query().select(Order.class).list();

        assertThat(backend.last().selection()).isEqualTo(new EntitySelection());
        assertThat(rows).containsExactly(order);
    }

    @Test
    @DisplayName("select(实体类).distinct()：拒绝，而不是让去重旗标静默失效")
    void entityTypeRejectsDistinct() {
        assertThatThrownBy(() -> query().distinct().select(Order.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SELECT DISTINCT")
                .hasMessageContaining("Order");
    }

    @Test
    @DisplayName("投影尝试失败也消耗 distinct 旗标：下一次投影回到不去重")
    void failedProjectionConsumesDistinctFlag() {
        EntityQuery<Order> query = query();
        assertThatThrownBy(() -> query.distinct().select(OrderView.class)).isInstanceOf(IllegalArgumentException.class);

        query.select(NameAndAge.class).list();

        assertThat(selection().distinct()).isFalse();
    }

    // ------------------------------------------------------------ 契约拒绝

    @Test
    @DisplayName("接口投影：拒绝（运行期代理不在本轮范围内）")
    void interfaceDtoRejected() {
        assertThatThrownBy(() -> query().select(OrderView.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("接口")
                .hasMessageContaining("OrderView");
    }

    @Test
    @DisplayName("抽象类投影：拒绝")
    void abstractDtoRejected() {
        assertThatThrownBy(() -> query().select(AbstractView.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("抽象类")
                .hasMessageContaining("AbstractView");
    }

    @Test
    @DisplayName("select((Class) null)：拒绝")
    void nullTypeRejected() {
        Class<NameAndAge> type = null;

        assertThatThrownBy(() -> query().select(type))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("type");
    }

    @Test
    @DisplayName("装配参数缺失：两个入参都当场拒绝")
    void projectionArgumentsRequired() {
        assertThatThrownBy(() -> DtoProjection.of(null, Order.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("type");
        assertThatThrownBy(() -> DtoProjection.of(NameAndAge.class, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entityType");
    }

    @Test
    @DisplayName("静态 setter 不是属性：只有静态 setter 的类按\"没有可映射的属性\"拒绝")
    void staticSettersAreNotProperties() {
        assertThatThrownBy(() -> query().select(StaticSetter.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可映射的属性")
                .hasMessageContaining("StaticSetter");
    }

    @Test
    @DisplayName("属性名按 JavaBeans 口径：setA → a，setAB → AB")
    void shortSetterNamesFollowJavaBeansRule() {
        query().select(ShortNames.class).list();

        assertThat(selection().items()).containsExactly(new PathProjection(path("AB")), new PathProjection(path("a")));
    }

    @Test
    @DisplayName("record 构造器抛异常：带出原始原因，消息点名 DTO")
    void recordConstructorFailureCarriesCause() {
        query().select(ThrowingRecord.class).list();

        assertThatThrownBy(() -> assemble(new Object[] {"ann"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ThrowingRecord")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("setter 抛异常：带出原始原因，消息点名 DTO 与 setter")
    void beanSetterFailureCarriesCause() {
        query().select(ThrowingSetter.class).list();

        assertThatThrownBy(() -> assemble(new Object[] {3}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ThrowingSetter")
                .hasMessageContaining("setAge");
    }

    // ---------------------------------------------------------------- fixture

    private ValueSelection selection() {
        return (ValueSelection) backend.last().selection();
    }

    /// 把一行原始行经投影装配成结果对象（与 `QueryBuilder#fetch` 同一条路径）。
    private List<Object> assemble(Object[] row) {
        // `List.of((Object) row)`：不转型时数组会被 varargs 摊平成一列一个元素
        return RowAssembler.assemble(List.of((Object) row), backend.last().selection());
    }

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

    /// 测试实体：只需要属性名，不需要任何注解。
    @SuppressWarnings("unused")
    private static final class Order {

        private Long id;
        private String name;
        private Integer age;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Integer getAge() {
            return age;
        }
    }

    private record NameAndAge(String name, Integer age) {}

    /// 列序按组件序：name 先、age 后，故意与实体属性序相反。
    private record SwappedTypes(Long name, String age) {}

    @SuppressWarnings("unused")
    private record Empty() {}

    @SuppressWarnings("unused")
    private static final class BeanDto {

        private String name;
        private Integer age;

        public void setName(String name) {
            this.name = name;
        }

        public void setAge(Integer age) {
            this.age = age;
        }

        String name() {
            return name;
        }

        Integer age() {
            return age;
        }
    }

    @SuppressWarnings("unused")
    private static final class UrlDto {

        private String url;

        public void setURL(String url) {
            this.url = url;
        }
    }

    @SuppressWarnings("unused")
    private static final class GetterOnly {

        public String getName() {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static final class NoDefaultConstructor {

        NoDefaultConstructor(String name) {
            // 只有带参构造器：反射拿不到无参构造器
        }

        public void setName(String name) {}
    }

    /// setter 参数类型与实体属性类型相反：装配在第一个 setter（age）上就失败。
    @SuppressWarnings("unused")
    private static final class SwapBeanDto {

        public void setAge(String age) {
            // 实体 age 是 Integer，这里声明成 String
        }

        public void setName(Integer name) {
            // 实体 name 是 String，这里声明成 Integer
        }
    }

    /// 只有静态 setter：静态方法不是属性，按"没有可映射的属性"拒绝。
    @SuppressWarnings("unused")
    private static final class StaticSetter {

        public static void setStatic(String value) {
            // 静态 setter 被排除
        }

        public void set(String value) {
            // 方法名太短（set 后无属性名），也被排除
        }
    }

    /// 单字母与全大写属性名：`setA` → `a`，`setAB` → `AB`。
    @SuppressWarnings("unused")
    private static final class ShortNames {

        public void setA(String a) {}

        public void setAB(String ab) {}
    }

    @SuppressWarnings("unused")
    private record ThrowingRecord(String name) {

        ThrowingRecord {
            throw new IllegalStateException("boom");
        }
    }

    @SuppressWarnings("unused")
    private static final class ThrowingSetter {

        public void setAge(Integer age) {
            throw new IllegalStateException("boom");
        }
    }

    private interface OrderView {

        String name();
    }

    @SuppressWarnings("unused")
    private abstract static class AbstractView {

        private String name;

        public String getName() {
            return name;
        }
    }
}
