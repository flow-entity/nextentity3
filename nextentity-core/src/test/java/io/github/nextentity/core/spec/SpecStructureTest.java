package io.github.nextentity.core.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Spec 节点结构的单元测试：不依赖数据库、不依赖 Spring。
@DisplayName("Spec 结构")
class SpecStructureTest {

    private static final AttributePath STATUS = AttributePath.of(String.class, "status");

    @Test
    @DisplayName("单段路径为直接字段，多段路径为嵌套字段")
    void pathDepth() {
        AttributePath nested = AttributePath.of(String.class, "customer", "orgId");

        assertThat(STATUS.isDirect()).isTrue();
        assertThat(STATUS.depth()).isEqualTo(1);
        assertThat(nested.isDirect()).isFalse();
        assertThat(nested.depth()).isEqualTo(2);
        assertThat(nested.names()).containsExactly("customer", "orgId");
    }

    @Test
    @DisplayName("空路径被拒绝")
    void emptyPathRejected() {
        assertThatThrownBy(() -> AttributePath.of(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("属性路径不能为空");
    }

    @Test
    @DisplayName("路径入参被复制为不可变列表")
    void pathIsImmutable() {
        assertThat(STATUS.names()).isUnmodifiable();
    }

    @Test
    @DisplayName("等值比较携带值")
    void eqCarriesValue() {
        Comparison comparison = Comparison.eq(STATUS, "CREATED");

        assertThat(comparison.operator()).isEqualTo(ComparisonOperator.EQ);
        assertThat(comparison.value()).isEqualTo("CREATED");
        assertThat(comparison.path()).isEqualTo(STATUS);
    }

    @Test
    @DisplayName("空值比较不携带值")
    void isNullCarriesNoValue() {
        Comparison comparison = Comparison.isNull(STATUS);

        assertThat(comparison.operator()).isEqualTo(ComparisonOperator.IS_NULL);
        assertThat(comparison.value()).isNull();
    }

    @Test
    @DisplayName("需要值的算子缺值时报错")
    void valueRequired() {
        assertThatThrownBy(() -> new Comparison(STATUS, ComparisonOperator.GT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需要比较值");
    }

    @Test
    @DisplayName("无值算子带值时报错")
    void valueForbidden() {
        assertThatThrownBy(() -> new Comparison(STATUS, ComparisonOperator.IS_NOT_NULL, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不接受比较值");
    }

    @Test
    @DisplayName("AND/OR 组合保留子节点顺序")
    void logicalCombination() {
        Comparison left = Comparison.eq(STATUS, "A");
        Comparison right = Comparison.isNull(STATUS);

        assertThat(Logical.and(left, right).operands()).containsExactly(left, right);
        assertThat(Logical.or(left, right).operator()).isEqualTo(LogicalOperator.OR);
        assertThat(Logical.not(left).operands()).containsExactly(left);
    }

    @Test
    @DisplayName("空组合被拒绝")
    void emptyLogicalRejected() {
        assertThatThrownBy(() -> new Logical(LogicalOperator.AND, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要一个操作数");
    }

    @Test
    @DisplayName("NOT 只接受一个操作数")
    void notAcceptsSingleOperand() {
        Comparison left = Comparison.eq(STATUS, "A");
        Comparison right = Comparison.eq(STATUS, "B");

        assertThatThrownBy(() -> new Logical(LogicalOperator.NOT, List.of(left, right)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT 只接受一个操作数");
    }

    @Test
    @DisplayName("结构相同的 Spec 相等")
    void structuralEquality() {
        assertThat(Comparison.eq(STATUS, "A")).isEqualTo(Comparison.eq(STATUS, "A"));
        assertThat(Comparison.eq(STATUS, "A")).isNotEqualTo(Comparison.eq(STATUS, "B"));
    }
}
