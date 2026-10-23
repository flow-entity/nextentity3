package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.entity.Customer;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `JpaPredicates.withExpression` 的 LIKE 分支（`compile/JpaPredicates.java:83`）。
///
/// 公开 api 造不出这个形状：`StringOps#like(String)` 只收字面量，`contains` 也是先拼成
/// `%v%` 再当字面量。因此这里用 `core.spec` 的 `Comparison` 节点直接拼「列 LIKE 列」，
/// 钉的是编译器的护栏（右值是表达式时 `builder.like` 没有渲染语义），不是某个公开用法。
class JpaPredicatesLikeTest extends JpaTestSupport {

    @Test
    @DisplayName("LIKE 的右侧是表达式：IllegalArgumentException，消息说明只收字面量")
    void rejectsExpressionPattern() {
        AttributePath name = AttributePath.of(Customer.class, "name");
        QuerySpec spec = new QuerySpec(
                Customer.class,
                new EntitySelection(),
                new Comparison(name, ComparisonOperator.LIKE, name),
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null);

        assertThatThrownBy(() -> inTx(entityManager -> compiler(entityManager).compile(spec)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LIKE 的右侧必须是字面量，不能是表达式");
    }

    private JpaCompiler compiler(EntityManager entityManager) {
        return new JpaCompiler(entityManager, meta());
    }
}
