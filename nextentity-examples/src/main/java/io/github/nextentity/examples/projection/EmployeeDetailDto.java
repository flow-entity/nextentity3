package io.github.nextentity.examples.projection;

import io.github.nextentity.examples.entity.Department;
import java.math.BigDecimal;

/// Class 投影示例 - 员工详细信息（多属性）。
///
/// 3.1.0 的 `select(Class)` 只做单层属性映射，不提供 `@Fetch` 的批量延迟加载；
/// 需要跨实体列或嵌套路径时用 `project(ProjectionMapper)`（见 EmployeeRepository#findEmployeeWithDepartmentInfo）。
///
/// **使用示例**：
/// ```java
/// List<EmployeeDetailDto> results = repository.query()
///     .select(EmployeeDetailDto.class)
///     .where(Employee::getActive).eq(true)
///     .list();
/// ```
public class EmployeeDetailDto {

    private Long id;
    private String name;
    private String email;
    private BigDecimal salary;
    private Boolean active;
    private Department department;

    /// 无参构造函数（`select(Class)` 投影必需）
    public EmployeeDetailDto() {}

    // ==================== Getter 方法 ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public BigDecimal getSalary() { return salary; }
    public void setSalary(BigDecimal salary) { this.salary = salary; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    /// 部门信息（3.1.0 不提供 `@Fetch` 延迟加载）
    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }
}