package io.github.nextentity.testing;

/// 关联嵌套路径用例的被查实体：只暴露断言需要的字段，不含后端类型。
///
/// `owner` 有意**不进**本接口：JPA 侧访问它会触发懒加载，而一致性用例只需要断 id/name。
public interface Asset {

    /// 主键。
    ///
    /// @return 主键值
    Long id();

    /// 资产名称。
    ///
    /// @return 名称
    String name();
}
