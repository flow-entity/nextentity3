package io.github.nextentity.core;

import java.util.function.Supplier;

/// 事务接缝：把"在事务里做一段事"抽象出来，实现由后端模块提供。
///
/// 为什么放在 `core`：core 与 jpa 不得依赖 `org.springframework.transaction`（DESIGN §6.7），
/// 需要事务的后端只能实现本接口，而不是把 Spring 的事务管理器拉进来。
///
/// 现状：`nextentity-jpa` 目前**没有**实现它，写路径的事务边界由调用方的 `@Transactional`
/// 决定（见 `spring.AbstractRepository#insert` 的说明），`spring` 也不注册本类型的 bean；
/// 因此本接口当前没有实现类，也没有调用点。
public interface TransactionOperations {

    /// 在事务内执行并返回结果。
    ///
    /// @param action 事务内动作，非 `null`
    /// @param <T>    结果类型
    /// @return `action` 的返回值，原样透传
    <T> T execute(Supplier<T> action);

    /// 在事务内执行，不返回结果。
    ///
    /// @param action 事务内动作，非 `null`
    void executeWithoutResult(Runnable action);
}
