package io.github.nextentity.core.spec;

/// 查询规格：不可变 AST 的根。
///
/// 只描述「查什么、怎么筛」，不携带任何后端类型；由编译器渲染成具体后端语句，
/// 因此同一份规格可以被单测断言、被日志输出、被不同后端复用。
public sealed interface Spec permits Comparison, Logical {}
