package io.github.nextentity.tests.jdbc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// legacy `Employee` 的形状（JDBC 侧）：`Long departmentId` 与 `@ManyToOne department` **映射同一列**
/// `department_id`，且关联带 `insertable = false, updatable = false`——只读副本，写入走标量字段。
///
/// `managerDepartment` 是**反向对照**：同样是指向部门的 owning 关联，但列可写。没有它的话，
/// 「写侧跳过只读关联」写错成「无条件跳过所有关联」也能让用例全绿。
///
/// JDBC 的 `WriteSql` 按 `isJoinColumnInsertable`/`isJoinColumnUpdatable` 过滤关联列，所以这条形状在
/// JDBC 侧从未炸过；但形状本身没有用例覆盖，任何一次"把关联也塞进列清单"的改动都会渲染出
/// `department_id` 出现两次的语句（H2/MySQL 会直接拒绝），所以把它钉进回归。
@Entity(name = "DualMappedEmployeeEntity")
@Table(name = "dual_mapped_employee")
public class DualMappedEmployeeEntity {

    @Id
    private Long id;

    private String name;

    @Column(name = "department_id")
    private Long departmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    private DualMappedDepartmentEntity department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_department_id")
    private DualMappedDepartmentEntity managerDepartment;

    protected DualMappedEmployeeEntity() {}

    public DualMappedEmployeeEntity(
            long id,
            String name,
            Long departmentId,
            DualMappedDepartmentEntity department,
            DualMappedDepartmentEntity managerDepartment) {
        this.id = id;
        this.name = name;
        this.departmentId = departmentId;
        this.department = department;
        this.managerDepartment = managerDepartment;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Long departmentId() {
        return departmentId;
    }

    public DualMappedDepartmentEntity department() {
        return department;
    }

    public DualMappedDepartmentEntity managerDepartment() {
        return managerDepartment;
    }
}
