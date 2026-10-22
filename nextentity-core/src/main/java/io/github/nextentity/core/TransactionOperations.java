package io.github.nextentity.core;

import java.util.function.Supplier;

/// 事务接缝：把"在事务里做一段事"抽象出来，实现由后端模块提供。
///
/// 保留理由：`nextentity-jpa` 的 `JpaTransactionOperations` 实现并消费它（写路径的事务边界），
/// 而 core/jpa 不能依赖 `org.springframework.transaction`（DESIGN §6.7）。
/// `spring` 模块**不**注册本类型的 bean：point-mall 的写方法走 `@Transactional`，
/// 事务管理器由 Boot 提供。
public interface TransactionOperations {

    /// 在事务内执行并返回结果。
    ///
    /// @param action 事务内动作
    /// @param <T>    结果类型
    /// @return 动作返回值
    <T> T execute(Supplier<T> action);

    /// 在事务内执行，不返回结果。
    ///
    /// @param action 事务内动作
    void executeWithoutResult(Runnable action);
}
