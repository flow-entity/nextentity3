package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Expression;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `ValueProjections` 的拒绝文案：同一处判据、两条消息，区别只在「带不带列下标」。
///
/// `of(...)` 服务 `select(...)` 的单表达式（`index == -1`），消息没有定位信息；
/// `ofAll(...)` 服务映射器的列集合（`index >= 0`），消息带「第 N 项」。
/// 两条文案都在这里逐字钉住——改文案就是改契约，必须同步改本类。
///
/// 正向路径（`Path` / `PathRef` / `Op` → 投影节点）的证据不在本类：
/// 见 `ProjectionMapperTest`、`QueryBuilderTest` 的 `select(...)` 用例。
@DisplayName("ValueProjections：不可解释表达式的拒绝文案")
class ValueProjectionsTest {

    @Test
    @DisplayName("of(不可解释表达式)：消息报类型与可接受形态，不带列下标")
    void singleExpressionRejected() {
        assertThatThrownBy(() -> ValueProjections.of(new ForeignExpression()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不支持的表达式类型: " + ForeignExpression.class.getName() + "（只接受 Path、PathRef 或 api 的聚合/算术值）");
    }

    @Test
    @DisplayName("ofAll(第 0 项不可解释)：同一判据的消息带列下标")
    void collectionExpressionRejectedWithIndex() {
        assertThatThrownBy(() -> ValueProjections.ofAll(List.of(new ForeignExpression())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("投影映射器的 expressions() 第 0 项不是库可解释的表达式: " + ForeignExpression.class.getName());
    }

    /// 既不是 `Path`、也不是 `PathRef`、也不是 `Op`（`Op` 是 sealed，外部实现不出来）的表达式。
    private record ForeignExpression() implements Expression<Object, String> {}
}
