package io.github.nextentity.examples.projection;

import io.github.nextentity.examples.entity.Department;

/// Class 投影示例 - 员工基本信息（POJO）。
///
/// 3.1.0 的 `select(Class)` 支持 record 或“无参构造 + setXxx 命名”的类投影，
/// 但不再提供 `@Fetch` / `CglibProxyInterceptor` 的延迟加载：`department` 按普通单层关联属性投影。
///
/// **使用示例**：
/// ```java
/// List<EmployeeInfoClass> results = repository.query()
///     .select(EmployeeInfoClass.class)
///     .where(Employee::getActive).eq(true)
///     .list();
/// ```
///
/// **对比 Interface 投影**：3.1.0 只提供类投影，接口投影尚未恢复（见 {@link EmployeeInfoInterface}）。
///
/// @see EmployeeInfoInterface 接口投影示例
public class EmployeeInfoClass {

    /// 员工 ID
    private Long id;

    /// 员工姓名
    private String name;

    /// 员工邮箱
    private String email;

    /// 部门信息（3.1.0 不提供 `@Fetch`，按普通关联属性投影）
    private Department department;

    /// 无参构造函数（`select(Class)` 投影必需）
    public EmployeeInfoClass() {}

    // ==================== Getter 方法 ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    /// 部门信息（3.1.0 不提供 `@Fetch` 延迟加载）
    ///
    /// @return 部门实体
    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }
}