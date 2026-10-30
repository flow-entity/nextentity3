package io.github.nextentity.api;

/// 可执行的条件批量操作：`UPDATE` / `DELETE` 这类不先取行、直接改库的语句。
///
/// 实现方在 [#execute()] 里把已构建好的条件与赋值渲染成一条语句并执行。
///
/// @see UpdateSetStep 条件更新
/// @see DeleteWhereStep 条件删除
public interface Executable {

    /// 执行已构建好的语句。
    ///
    /// @return 受影响的行数
    int execute();
}
