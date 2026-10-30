package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Path;
import io.github.nextentity.core.spec.Assignment;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.DeleteSpec;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.UpdateSpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 条件更新/删除构建器 → 写语句规格的结构断言：不连接数据库，只把规格截下来看形状。
///
/// 条件部分复用查询的算子（`where(...)` 的 5 个重载、`eq` / `startsWith` / `*IfNotNull` 短路），
/// 因此这里重点钉「赋值如何累积」与「条件如何合并」两件事。
@DisplayName("条件更新/删除 → Spec")
class UpdateDeleteStepBuilderTest {

    private final List<UpdateSpec> updates = new ArrayList<>();
    private final List<DeleteSpec> deletes = new ArrayList<>();

    private UpdateSetStepBuilder<Order> update(int affected) {
        return new UpdateSetStepBuilder<>(Order.class, spec -> {
            updates.add(spec);
            return affected;
        });
    }

    private DeleteWhereStepBuilder<Order> delete(int affected) {
        return new DeleteWhereStepBuilder<>(Order.class, spec -> {
            deletes.add(spec);
            return affected;
        });
    }

    private static AttributePath path(String... names) {
        return AttributePath.of(Order.class, names);
    }

    // ------------------------------------------------------------------ update

    @Test
    @DisplayName("set(方法引用)：一条赋值，实体类型与受影响行数原样透传")
    void setCarriesAssignment() {
        int affected = update(3).set(Order::getStatus, "ARCHIVED").execute();

        assertThat(affected).isEqualTo(3);
        assertThat(updates).hasSize(1);
        UpdateSpec spec = updates.getFirst();
        assertThat(spec.entityType()).isEqualTo(Order.class);
        assertThat(spec.assignments()).containsExactly(new Assignment(path("status"), "ARCHIVED"));
        assertThat(spec.where()).isNull();
        // 另读一遍访问器：equals 只比合成字段，读不到「访问器被改成返回 null」这类变异
        Assignment assignment = spec.assignments().getFirst();
        assertThat(assignment.path()).isEqualTo(path("status"));
        assertThat(assignment.value()).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("set(Path)：嵌套列保留整条属性链")
    void setNestedPathKeepsChain() {
        update(1)
                .set(EntityPath.of(Order::getCustomer).get(Customer::getName), "ann")
                .execute();

        assertThat(updates.getFirst().assignments()).containsExactly(new Assignment(path("customer", "name"), "ann"));
    }

    @Test
    @DisplayName("set(…, null)：置 NULL 记成 null 值，而不是跳过这一列")
    void setNullValue() {
        update(1)
                .set(EntityPath.of(Order::getCustomer).get(Customer::getId), null)
                .execute();

        assertThat(updates.getFirst().assignments()).containsExactly(new Assignment(path("customer", "id"), null));
        // 置空是「值就是 null」，不是「这一列没被赋值」：访问器必须原样给出 null
        Assignment assignment = updates.getFirst().assignments().getFirst();
        assertThat(assignment.path()).isEqualTo(path("customer", "id"));
        assertThat(assignment.value()).isNull();
    }

    @Test
    @DisplayName("多个 set：按调用顺序累积")
    void multipleSetsKeepOrder() {
        update(2)
                .set(Order::getStatus, "A")
                .set(Order::getQuantity, 5)
                .set(Order::getNote, null)
                .execute();

        assertThat(updates.getFirst().assignments())
                .containsExactly(
                        new Assignment(path("status"), "A"),
                        new Assignment(path("quantity"), 5),
                        new Assignment(path("note"), null));
    }

    @Test
    @DisplayName("where(eq)：条件进入写语句规格")
    void whereEqBuildsCondition() {
        update(1).set(Order::getStatus, "A").where(Order::getStatus).eq("OLD").execute();

        assertThat(updates.getFirst().where())
                .isEqualTo(new Comparison(path("status"), ComparisonOperator.EQ, Literal.of("OLD")));
    }

    @Test
    @DisplayName("where(方法引用)：非字符串属性走 PathOperator 重载（字符串属性走 StringOperator 重载）")
    void wherePathRefNumericOperator() {
        update(1).set(Order::getStatus, "A").where(Order::getQuantity).gt(0).execute();

        assertThat(updates.getFirst().where())
                .isEqualTo(new Comparison(path("quantity"), ComparisonOperator.GT, Literal.of(0)));
    }

    @Test
    @DisplayName("where(startsWith)：字符串算子族在写语句里同样可用")
    void whereStringOperatorAvailable() {
        update(1)
                .set(Order::getStatus, "A")
                .where(Order::getStatus)
                .startsWith("ann")
                .execute();

        assertThat(updates.getFirst().where())
                .isEqualTo(new Comparison(path("status"), ComparisonOperator.LIKE, Literal.of("ann%")));
    }

    @Test
    @DisplayName("where(isNull)：空值判断在写语句里同样可用")
    void whereNullCheckAvailable() {
        delete(1).where(Order::getNote).isNull().execute();

        assertThat(deletes.getFirst().where()).isEqualTo(new NullCheck(path("note"), false));
    }

    @Test
    @DisplayName("where(表达式) 与 where(算子) 混合：多个条件做 AND")
    void conditionsMergeWithAnd() {
        update(1)
                .set(Order::getStatus, "A")
                .where(Path.of(Order::getQuantity).gt(3))
                .where(Order::getStatus)
                .eq("OLD")
                .execute();

        assertThat(updates.getFirst().where()).isInstanceOf(Logical.class);
        Logical where = (Logical) updates.getFirst().where();
        assertThat(where.operator()).isEqualTo(LogicalOperator.AND);
        assertThat(where.operands()).hasSize(2);
    }

    @Test
    @DisplayName("*IfNotNull(null)：整条条件不进语句（写语句里尤其不能变成 IS NULL 或整表更新）")
    void ifNotNullSkipsNull() {
        update(1)
                .set(Order::getStatus, "A")
                .where(Order::getStatus)
                .eqIfNotNull(null)
                .execute();

        assertThat(updates.getFirst().where()).isNull();
    }

    @Test
    @DisplayName("非 Op 的布尔表达式进 where(Expression)：当场拒绝")
    void foreignExpressionRejected() {
        UpdateSetStepBuilder<Order> builder = update(1);
        builder.set(Order::getStatus, "A");
        Expression<Order, Boolean> handWritten = new Expression<>() {};

        assertThatThrownBy(() -> builder.where(handWritten))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("where(...)");
    }

    @Test
    @DisplayName("where(Path)：Path 重载按 refs() 解析，再叠算子")
    void wherePathOverloadBuildsCondition() {
        update(1)
                .set(Order::getStatus, "A")
                .where(Path.of(Order::getQuantity))
                .gt(3)
                .execute();

        assertThat(updates.getFirst().where())
                .isEqualTo(new Comparison(path("quantity"), ComparisonOperator.GT, Literal.of(3)));
    }

    @Test
    @DisplayName("where(StringPath)：字符串路径重载也能拿到模式算子")
    void whereStringPathOverloadAvailable() {
        delete(1)
                .where(EntityPath.of(Order::getCustomer).get(Customer::getName))
                .startsWith("ann")
                .execute();

        assertThat(deletes.getFirst().where())
                .isEqualTo(new Comparison(path("customer", "name"), ComparisonOperator.LIKE, Literal.of("ann%")));
    }

    @Test
    @DisplayName("where(被短路条件)：占位谓词不加条件，写语句不因它退回整表")
    void wherePlaceholderSkipped() {
        update(1)
                .set(Order::getStatus, "A")
                .where(Path.of(Order::getQuantity).eqIfNotNull(null))
                .execute();

        assertThat(updates.getFirst().where()).isNull();
    }

    // ------------------------------------------------------------------ delete

    @Test
    @DisplayName("delete(where)：条件与实体类型进入删除规格，无 where 时为无条件删除")
    void deleteBuildsSpec() {
        int affected = delete(4).where(Order::getStatus).eq("INACTIVE").execute();

        assertThat(affected).isEqualTo(4);
        assertThat(deletes.getFirst().entityType()).isEqualTo(Order.class);
        assertThat(deletes.getFirst().where())
                .isEqualTo(new Comparison(path("status"), ComparisonOperator.EQ, Literal.of("INACTIVE")));

        delete(2).execute();
        assertThat(deletes.get(1).where()).isNull();
    }

    @Test
    @DisplayName("delete 多条件：AND 合并；表达式与算子可以混用")
    void deleteMergesConditions() {
        delete(1)
                .where(Order::getStatus)
                .eq("INACTIVE")
                .where(Path.of(Order::getQuantity).lt(1))
                .execute();

        Logical where = (Logical) deletes.getFirst().where();
        assertThat(where.operator()).isEqualTo(LogicalOperator.AND);
        assertThat(where.operands()).hasSize(2);
    }

    @SuppressWarnings("unused")
    static final class Order {

        private Long id;
        private String status;
        private Integer quantity;
        private String note;
        private Customer customer;

        public Long getId() {
            return id;
        }

        public String getStatus() {
            return status;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public String getNote() {
            return note;
        }

        public Customer getCustomer() {
            return customer;
        }
    }

    @SuppressWarnings("unused")
    static final class Customer {

        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }
}
