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
    /// @param mode 锁模式，非 `null`；只作用于取行的方法，`count()` / `exists()` 会清掉锁
    /// @return 终止操作（`Collector`），锁随后端渲染
    Collector<T> lock(LockMode mode);
}
