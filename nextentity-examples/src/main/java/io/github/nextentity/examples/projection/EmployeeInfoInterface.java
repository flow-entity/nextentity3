package io.github.nextentity.examples.projection;

import io.github.nextentity.examples.entity.Department;

/// Interface 投影示例（2.2.3 语义，3.1.0 尚未恢复）。
///
/// 2.2.3 用 JDK Proxy 为接口投影提供实例并支持 `@Fetch` 延迟加载；3.1.0 的 `SelectStep`
/// 只支持 record / “无参构造 + setXxx” 的类投影，接口与抽象类不提供，因此本接口仅作历史示例保留，
/// 当前 `select(EmployeeInfoInterface.class)` 会抛 `UnsupportedOperationException`。
/// 需要同形状结果时请用 record 投影或 `project(ProjectionMapper)` 手工映射。
///
/// @see EmployeeInfoClass 类投影对比示例
public interface EmployeeInfoInterface {

    /// 员工 ID
    Long getId();

    /// 员工姓名
    String getName();

    /// 员工邮箱
    String getEmail();

    /// 部门信息（2.2.3 为延迟加载属性；3.1.0 不提供 `@Fetch`）
    Department getDepartment();
}