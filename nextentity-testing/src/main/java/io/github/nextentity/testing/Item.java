package io.github.nextentity.testing;

/// 一致性用例的固定夹具模型。
///
/// `nextentity-testing` 用它表达「断言读哪些字段」，后端负责把自己的实体映射成这个形状
/// （`nextentity-tests-jpa` 里是一个 `@Entity` 实现类）。
///
/// 设计要点：一致性基类只认这个接口 + `api` + `core` 的接缝类型，**任何后端类型都不得出现在这里**，
/// 否则新增后端就必须改基类（DESIGN §5 / §6-8）。
public interface Item {

    /// 主键。
    ///
    /// @return 主键值
    Long id();

    /// 可比较、可模糊匹配的名称。
    ///
    /// @return 名称
    String name();

    /// 可比较、可为空的数值（用于验证 `null` 参与的区间条件）。
    ///
    /// @return 数值或 `null`
    Integer score();

    /// 可为空的文本（用于验证 `isNull` / `isNotNull`）。
    ///
    /// @return 文本或 `null`
    String note();
}
