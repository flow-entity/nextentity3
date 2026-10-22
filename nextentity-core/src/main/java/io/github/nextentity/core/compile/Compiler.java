package io.github.nextentity.core.compile;

import io.github.nextentity.core.spec.QuerySpec;

/// 编译器接缝：把不可变规格渲染成某个后端的语句。
///
/// 泛型 `S` 由后端定义（JPA 为 Criteria 持有者，将来的 SQL 后端为 `{sql, bindings}`），
/// core 不约束其内容——这样第二后端只需要多一个实现，而不是多一套查询模型。
///
/// @param <S> 后端语句类型
public interface Compiler<S> {

    /// 编译一次查询。
    ///
    /// 输入恒为完整规格（from 实体、查询形态、条件、分组、排序、窗口、锁），
    /// 计数与存在性所需的"清排序/清窗口/去锁"已在 {@link QuerySpec#forCount()} /
    /// {@link QuerySpec#forExists()} 中完成，后端不必再判断。
    ///
    /// @param spec 完整规格，非 `null`；本方法只读，不修改它
    /// @return 后端语句
    S compile(QuerySpec spec);
}
