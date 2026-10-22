package io.github.nextentity.api;

/// 加锁入口。
///
/// 只接受自研 {@link LockMode}：`api` 不依赖任何持久化/后端类型，
/// 由后端实现（`nextentity-jpa`）把枚举映射成自身的锁模式。
///
/// @param <T> 结果类型
public interface LockStep<T> extends Collector<T> {

    /// 以给定锁模式执行查询。
    ///
    /// @param mode 锁模式
    /// @return 终止操作
    Collector<T> lock(LockMode mode);
}
