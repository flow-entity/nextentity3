package io.github.nextentity.api;

import jakarta.persistence.LockModeType;

/// 加锁入口。
///
/// @param <T> 结果类型
public interface LockStep<T> extends Collector<T> {

    /// 以给定锁模式执行查询。
    ///
    /// @param mode 锁模式
    /// @return 终止操作
    Collector<T> lock(LockMode mode);

    /// 兼容 2.2.3 的重载：把 `jakarta.persistence.LockModeType` 转成自研枚举。
    ///
    /// 保留它是因为 point-mall 有 3 处存量调用点直接传 `LockModeType`，
    /// 而 `api` 不能把 jakarta 类型扩散到 `core` 与 `core.spec`。
    ///
    /// @param mode jakarta 锁模式
    /// @return 终止操作
    default Collector<T> lock(LockModeType mode) {
        return lock(LockMode.valueOf(mode.name()));
    }
}
