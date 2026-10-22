package io.github.nextentity.core.spec;

/// 条件规格：不可变 AST 的根。
///
/// 只描述"筛什么行"，不携带任何后端类型；由编译器渲染成具体后端语句，
/// 因此同一份规格可以被单测断言、被日志输出、被不同后端复用。
///
/// 节点集合按 point-mall 的真实调用点裁剪（见 `tmp/contract/META-NOTES.md` §4），
/// 新增节点必须同时让编译器的 `switch` 穷尽性检查报错，以此逼出全部分支。
public sealed interface Spec permits Comparison, Between, In, NullCheck, Logical, Constant {}
