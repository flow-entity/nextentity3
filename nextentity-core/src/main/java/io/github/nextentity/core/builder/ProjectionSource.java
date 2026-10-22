package io.github.nextentity.core.builder;

import io.github.nextentity.core.spec.Projection;

/// 内部契约：表达式对象携带的投影项。
///
/// `select(...)` 接受 `api.Expression`，但 AST 需要区分"裸路径"与"聚合"，因此由表达式自己报告投影形态。
interface ProjectionSource {

    /// 该表达式作为投影项时的 AST 节点。
    ///
    /// @return 投影项
    Projection projection();
}
