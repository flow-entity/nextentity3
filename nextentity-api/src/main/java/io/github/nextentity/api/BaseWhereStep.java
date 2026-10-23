package io.github.nextentity.api;

/// 查询链的公共底座：条件、排序、分组、HAVING、分页、锁与终止操作都在这里汇合。
///
/// @param <T> 根实体类型
/// @param <U> 结果类型
public interface BaseWhereStep<T, U>
        extends Whereable<T, BaseWhereStep<T, U>>,
                OrderByStep<T, U>,
                GroupByStep<T, U>,
                HavingStep<T, U>,
                SliceStep<U>,
                LockStep<U> {}
