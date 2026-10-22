package io.github.nextentity.core.builder;

import io.github.nextentity.core.spec.Spec;

/// 内部契约：表达式对象携带的布尔 AST 节点。
///
/// `api.Expression` 是纯标记接口，拿不到 AST；查询构建器用它把 `where(Expression)` 的参数
/// 还原成 `Spec`。2.2.3 用的是 `ExpressionTree.getRoot()`，同一思路。
interface SpecSource {

    /// 该表达式对应的条件节点。
    ///
    /// @return 条件节点
    Spec spec();
}
