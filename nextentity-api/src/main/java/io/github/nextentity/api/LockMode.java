package io.github.nextentity.api;

/// 锁模式。
///
/// 与具体持久化后端解耦：JPA 后端映射为 `jakarta.persistence.LockModeType`，
/// 其它后端按自身能力映射或直接拒绝，因此本枚举不引用任何后端类型。
public enum LockMode {

    /// 乐观锁。
    OPTIMISTIC,

    /// 乐观锁并强制递增版本。
    OPTIMISTIC_FORCE_INCREMENT,

    /// 悲观读锁。
    PESSIMISTIC_READ,

    /// 悲观写锁。
    PESSIMISTIC_WRITE,

    /// 悲观写锁并强制递增版本。
    PESSIMISTIC_FORCE_INCREMENT
}
