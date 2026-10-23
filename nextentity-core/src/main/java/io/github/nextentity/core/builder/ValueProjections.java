package io.github.nextentity.core.builder;

import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Op;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.Projection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// api 表达式 → `core.spec.Projection` 的唯一转换点，映射器的列集合也在这里整批校验。
///
/// 列集合的契约（非空、非 `null`、后端可解释）必须在 `project()` 阶段失败，不能拖到编译或执行，
/// 所以校验与转换放在一起：调用方拿到的列表要么整体可用，要么当场抛错。
///
/// 非目标：不解释表达式语义（聚合/算术仍归 [OpInterpreter]），也不生成任何 SQL/方言片段——
/// 这里只决定"这一列变成哪种投影节点"。
final class ValueProjections {

    private static final String EXPRESSIONS_AT = "投影映射器的 expressions() 第 ";

    private ValueProjections() {}

    /// 单个表达式 → 投影节点。
    ///
    /// @param expression 表达式，非 `null`
    /// @return 属性列（`Path` / `PathRef`）的 `PathProjection`，或聚合/算术的投影节点
    /// @throws IllegalArgumentException 既不是 `Path`、`PathRef` 也不是 `Op`
    static Projection of(Expression<?, ?> expression) {
        return of(expression, -1);
    }

    /// 映射器的列集合 → 投影节点列表，顺序即结果列顺序。
    ///
    /// @param expressions 映射器的 `expressions()` 返回值，非 `null`
    /// @return 非空、与入参等长且顺序一致的投影节点列表
    /// @throws IllegalArgumentException 列表为空、某元素为 `null`、某元素不可解释；后两者的消息带列下标
    static List<Projection> ofAll(List<? extends Expression<?, ?>> expressions) {
        Objects.requireNonNull(expressions, "expressions()");
        if (expressions.isEmpty()) {
            throw new IllegalArgumentException("投影映射器的 expressions() 不能为空");
        }
        List<Projection> items = new ArrayList<>(expressions.size());
        for (int i = 0; i < expressions.size(); i++) {
            Expression<?, ?> expression = expressions.get(i);
            if (expression == null) {
                throw new IllegalArgumentException(EXPRESSIONS_AT + i + " 项为 null");
            }
            items.add(of(expression, i));
        }
        return items;
    }

    /// 转换的真实实现：`index >= 0` 表示这次转换来自映射器的列集合，拒绝原因要带上「第 N 项」以便定位。
    ///
    /// 可解释形态只在这里列一次；批量校验若另写一份判据，新增形态时必然漂移。
    private static Projection of(Expression<?, ?> expression, int index) {
        if (expression instanceof Path<?, ?> path) {
            return new PathProjection(PathRefs.resolve(path.refs()));
        }
        if (expression instanceof PathRef<?, ?> ref) {
            return new PathProjection(PathRefs.resolve(ref));
        }
        if (expression instanceof Op op) {
            return OpInterpreter.toProjection(op);
        }
        String type = expression.getClass().getName();
        throw new IllegalArgumentException(
                index < 0
                        ? "不支持的表达式类型: " + type + "（只接受 Path、PathRef 或 api 的聚合/算术值）"
                        : EXPRESSIONS_AT + index + " 项不是库可解释的表达式: " + type);
    }
}
