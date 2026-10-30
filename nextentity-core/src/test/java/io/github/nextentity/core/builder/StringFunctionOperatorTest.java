package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Path;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.StringFunction;
import io.github.nextentity.core.spec.StringFunctionOperand;
import io.github.nextentity.core.spec.SubstringOperand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 字符串模式算子与函数算子的**节点形状**：`where(X::getName)` 之后的
/// `startsWith / endsWith / notXxx / lower / upper / trim / substring / length`。
///
/// 这些算子不新增 `ComparisonOperator`：前缀/后缀/否定都在 builder 层拼成 `LIKE` 模式
/// 或对 `LIKE` 取反（[Logical] `NOT`），函数则把条件主语换成 [StringFunctionOperand] / [SubstringOperand]。
/// 断言对象是 spec 节点树——语料只能证明"形状"，正确性由后端渲染波次的落库用例承担。
@DisplayName("StringStepOperator：模式算子与字符串函数的节点形状")
class StringFunctionOperatorTest {

    private final Recorder backend = new Recorder();

    private EntityQuery<Order> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    // ------------------------------------------------ 前缀/后缀/否定（LIKE 模式）

    @Test
    @DisplayName("startsWith(prefix)：拼成 prefix% 的 LIKE，主语是属性路径")
    void startsWithWrapsSuffixWildcard() {
        query().where(Order::getName).startsWith("ann").list();

        assertThat(where()).isEqualTo(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("ann%")));
    }

    @Test
    @DisplayName("endsWith(suffix)：拼成 %suffix 的 LIKE")
    void endsWithWrapsPrefixWildcard() {
        query().where(Order::getName).endsWith("son").list();

        assertThat(where()).isEqualTo(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("%son")));
    }

    @Test
    @DisplayName("notLike(pattern)：模式原样，外面套一层 NOT")
    void notLikeWrapsNot() {
        query().where(Order::getName).notLike("A%").list();

        assertThat(where())
                .isEqualTo(Logical.not(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("A%"))));
    }

    @Test
    @DisplayName("notStartsWith / notEndsWith / notContains：各自的 % pattern 再取反")
    void negativePatternFamilyWrapsNot() {
        query().where(Order::getName).notStartsWith("ann").list();
        assertThat(where())
                .isEqualTo(Logical.not(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("ann%"))));

        backend.specs.clear();
        query().where(Order::getName).notEndsWith("son").list();
        assertThat(where())
                .isEqualTo(Logical.not(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("%son"))));

        backend.specs.clear();
        query().where(Order::getName).notContains("nn").list();
        assertThat(where())
                .isEqualTo(Logical.not(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("%nn%"))));
    }

    // ----------------------------------------------------- *IfNotNull / *IfNotEmpty

    @Test
    @DisplayName("*IfNotNull(null)：整个谓词短路，WHERE 为空（不是 IS NULL）")
    void patternIfNotNullSkipsNull() {
        query().where(Order::getName).startsWithIfNotNull(null).list();
        assertThat(where()).isNull();

        backend.specs.clear();
        query().where(Order::getName).notLikeIfNotNull(null).list();
        assertThat(where()).isNull();

        backend.specs.clear();
        query().where(Order::getName).notStartsWithIfNotNull(null).list();
        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("*IfNotEmpty(null/\"\")：空值短路，一个节点都不产生")
    void patternIfNotEmptySkipsEmpty() {
        query().where(Order::getName).endsWithIfNotEmpty(null).list();
        assertThat(where()).isNull();

        backend.specs.clear();
        query().where(Order::getName).endsWithIfNotEmpty("").list();
        assertThat(where()).isNull();

        backend.specs.clear();
        query().where(Order::getName).notContainsIfNotEmpty("").list();
        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("*IfNotEmpty(非空串)：条件照常产生（短路条件不是恒真）")
    void patternIfNotEmptyKeepsValue() {
        query().where(Order::getName).notContainsIfNotEmpty("nn").list();

        assertThat(where())
                .isEqualTo(Logical.not(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("%nn%"))));
    }

    // ------------------------------------------------------------- 字符串函数

    @Test
    @DisplayName("lower().contains(v)：条件主语是 LOWER(name)，不是 name")
    void lowerMovesSubjectIntoFunction() {
        query().where(Order::getName).lower().contains("ann").list();

        assertThat(where())
                .isEqualTo(new Comparison(
                        new StringFunctionOperand(StringFunction.LOWER, path("name")),
                        ComparisonOperator.LIKE,
                        Literal.of("%ann%")));
    }

    @Test
    @DisplayName("lower().trim()：函数可嵌套，外层包内层")
    void functionsNest() {
        query().where(Order::getName).lower().trim().eq("ann").list();

        assertThat(where())
                .isEqualTo(new Comparison(
                        new StringFunctionOperand(
                                StringFunction.TRIM, new StringFunctionOperand(StringFunction.LOWER, path("name"))),
                        ComparisonOperator.EQ,
                        Literal.of("ann")));
    }

    @Test
    @DisplayName("substring(offset, length)：带长度；substring(offset)：length 为 null 表示截到末尾")
    void substringCarriesWindow() {
        query().where(Order::getName).substring(2, 3).eq("nne").list();
        assertThat(where())
                .isEqualTo(new Comparison(
                        new SubstringOperand(path("name"), 2, 3), ComparisonOperator.EQ, Literal.of("nne")));

        backend.specs.clear();
        query().where(Order::getName).substring(2).eq("nne").list();
        // 断言的是"省略"这件事本身：length == null，不是一个与数据库上限绑死的巨型数字
        assertThat(where())
                .isEqualTo(new Comparison(
                        new SubstringOperand(path("name"), 2, null), ComparisonOperator.EQ, Literal.of("nne")));
    }

    @Test
    @DisplayName("length().gt(n)：数字算子的比较落到 LENGTH(name) 主语上")
    void lengthSubjectComparesAsNumber() {
        query().where(Order::getName).length().gt(5).list();

        assertThat(where())
                .isEqualTo(new Comparison(
                        new StringFunctionOperand(StringFunction.LENGTH, path("name")),
                        ComparisonOperator.GT,
                        Literal.of(5)));
    }

    @Test
    @DisplayName("length().between(min,max) / notBetween：区间主语是长度，取反套 NOT")
    void lengthSubjectInBetween() {
        query().where(Order::getName).length().between(2, 4).list();
        StringFunctionOperand length = new StringFunctionOperand(StringFunction.LENGTH, path("name"));
        assertThat(where()).isEqualTo(new Between(length, Literal.of(2), Literal.of(4)));

        backend.specs.clear();
        query().where(Order::getName).length().notBetween(2, 4).list();
        assertThat(where())
                .isEqualTo(Logical.not(new Between(
                        new StringFunctionOperand(StringFunction.LENGTH, path("name")), Literal.of(2), Literal.of(4))));
    }

    @Test
    @DisplayName("length().between(表达式, 表达式)：两侧都翻译成操作数")
    void lengthBetweenExpressions() {
        query().where(Order::getName)
                .length()
                .between(Path.of(Order::getAge), Path.of(Order::getAge))
                .list();

        assertThat(where())
                .isEqualTo(new Between(
                        new StringFunctionOperand(StringFunction.LENGTH, path("name")), path("age"), path("age")));
    }

    @Test
    @DisplayName("lower().length().gt(n)：函数链再取长度，主语是 LENGTH(LOWER(name))")
    void chainedFunctionThenLength() {
        query().where(Order::getName).lower().length().gt(3).list();

        assertThat(where())
                .isEqualTo(new Comparison(
                        new StringFunctionOperand(
                                StringFunction.LENGTH, new StringFunctionOperand(StringFunction.LOWER, path("name"))),
                        ComparisonOperator.GT,
                        Literal.of(3)));
    }

    @Test
    @DisplayName("length() 的比较算子全集：eq/ne/ge/gt/le/lt 各自落到 ComparisonOperator")
    void numberOperatorCoversComparisonForms() {
        StringFunctionOperand length = new StringFunctionOperand(StringFunction.LENGTH, path("name"));

        query().where(Order::getName).length().eq(3).list();
        assertThat(where()).isEqualTo(new Comparison(length, ComparisonOperator.EQ, Literal.of(3)));

        backend.specs.clear();
        query().where(Order::getName).length().ne(3).list();
        assertThat(where()).isEqualTo(new Comparison(length, ComparisonOperator.NE, Literal.of(3)));

        backend.specs.clear();
        query().where(Order::getName).length().ge(3).list();
        assertThat(where()).isEqualTo(new Comparison(length, ComparisonOperator.GE, Literal.of(3)));

        backend.specs.clear();
        query().where(Order::getName).length().le(3).list();
        assertThat(where()).isEqualTo(new Comparison(length, ComparisonOperator.LE, Literal.of(3)));

        backend.specs.clear();
        query().where(Order::getName).length().lt(3).list();
        assertThat(where()).isEqualTo(new Comparison(length, ComparisonOperator.LT, Literal.of(3)));
    }

    @Test
    @DisplayName("length() 的 *IfNotNull：非 null 照常比较，null 短路成无条件")
    void numberOperatorIfNotNull() {
        StringFunctionOperand length = new StringFunctionOperand(StringFunction.LENGTH, path("name"));

        query().where(Order::getName).length().eqIfNotNull(3).list();
        assertThat(where()).isEqualTo(new Comparison(length, ComparisonOperator.EQ, Literal.of(3)));

        backend.specs.clear();
        query().where(Order::getName).length().geIfNotNull(3).list();
        assertThat(where()).isEqualTo(new Comparison(length, ComparisonOperator.GE, Literal.of(3)));

        backend.specs.clear();
        query().where(Order::getName).length().gtIfNotNull(3).list();
        assertThat(where()).isEqualTo(new Comparison(length, ComparisonOperator.GT, Literal.of(3)));

        backend.specs.clear();
        query().where(Order::getName).length().leIfNotNull(3).list();
        assertThat(where()).isEqualTo(new Comparison(length, ComparisonOperator.LE, Literal.of(3)));

        backend.specs.clear();
        query().where(Order::getName).length().ltIfNotNull(3).list();
        assertThat(where()).isEqualTo(new Comparison(length, ComparisonOperator.LT, Literal.of(3)));

        backend.specs.clear();
        query().where(Order::getName).length().eqIfNotNull(null).list();
        assertThat(where()).isNull();

        backend.specs.clear();
        query().where(Order::getName).length().geIfNotNull(null).list();
        assertThat(where()).isNull();

        backend.specs.clear();
        query().where(Order::getName).length().gtIfNotNull(null).list();
        assertThat(where()).isNull();

        backend.specs.clear();
        query().where(Order::getName).length().leIfNotNull(null).list();
        assertThat(where()).isNull();

        backend.specs.clear();
        query().where(Order::getName).length().ltIfNotNull(null).list();
        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("upper()/trim()：与 lower() 同构，主语换成对应函数节点")
    void upperAndTrimMoveSubjectAsLowerDoes() {
        query().where(Order::getName).upper().eq("ANN").list();
        assertThat(where())
                .isEqualTo(new Comparison(
                        new StringFunctionOperand(StringFunction.UPPER, path("name")),
                        ComparisonOperator.EQ,
                        Literal.of("ANN")));

        backend.specs.clear();
        query().where(Order::getName).trim().eq("ann").list();
        assertThat(where())
                .isEqualTo(new Comparison(
                        new StringFunctionOperand(StringFunction.TRIM, path("name")),
                        ComparisonOperator.EQ,
                        Literal.of("ann")));
    }

    @Test
    @DisplayName("notLikeIfNotNull(非 null)：走 notLike 的取反，不短路")
    void notLikeIfNotNullKeepsValue() {
        query().where(Order::getName).notLikeIfNotNull("A%").list();

        assertThat(where())
                .isEqualTo(Logical.not(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("A%"))));
    }

    @Test
    @DisplayName("where(Path) / where(StringPath)：两条 Path 重载各自解析成属性路径")
    void pathOverloadsResolveToAttributePath() {
        query().where(Path.of(Order::getName)).eq("ann").list();
        assertThat(where()).isEqualTo(new Comparison(path("name"), ComparisonOperator.EQ, Literal.of("ann")));

        backend.specs.clear();
        query().where(EntityPath.of(Order::getName)).eq("ann").list();
        assertThat(where()).isEqualTo(new Comparison(path("name"), ComparisonOperator.EQ, Literal.of("ann")));
    }

    // ------------------------------------------------------------ 契约拒绝

    @Test
    @DisplayName("函数结果上 in(...) 被拒绝：IN 的主语只能是属性路径")
    void inOnFunctionSubjectRejected() {
        assertThatThrownBy(() -> query().where(Order::getName).lower().in(List.of("a")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("in(...)");
    }

    @Test
    @DisplayName("函数结果上 isNull() 被拒绝：函数结果没有\"列为 null\"的语义")
    void nullnessOnFunctionSubjectRejected() {
        assertThatThrownBy(() -> query().where(Order::getName).trim().isNull())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("isNull()");
    }

    @Test
    @DisplayName("notBetween 的节点是 NOT(Between)，不是新算子")
    void logicalNotWrapsSingleOperand() {
        query().where(Order::getName).length().notBetween(1, 2).list();

        Logical spec = (Logical) where();
        assertThat(spec.operator()).isEqualTo(LogicalOperator.NOT);
        assertThat(spec.operands()).hasSize(1);
    }

    // ---------------------------------------------------------------- fixture

    private Spec where() {
        return backend.last().where();
    }

    private static AttributePath path(String name) {
        return AttributePath.of(Order.class, name);
    }

    private static EntityType<Order> meta() {
        EntityAttribute id = new EntityAttribute() {
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
        return new EntityType<>() {
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

    private static final class Recorder implements Compiler<QuerySpec>, Executor<QuerySpec> {

        private final List<QuerySpec> specs = new ArrayList<>();

        @Override
        public QuerySpec compile(QuerySpec spec) {
            return spec;
        }

        @Override
        public <R> List<R> list(QuerySpec query) {
            specs.add(query);
            return List.of();
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
}
