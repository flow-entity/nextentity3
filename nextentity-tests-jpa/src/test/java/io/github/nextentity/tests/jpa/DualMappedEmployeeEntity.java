package io.github.nextentity.tests.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// legacy `Employee` 的形状：`Long departmentId` 与 `@ManyToOne department` **映射同一列**
/// （`departmentId`），且关联带 `insertable = false, updatable = false`——只读副本，写入走标量字段。
///
/// `managerDepartment` 是**反向对照**：同样是指向部门的 owning 关联，但列可写。没有它的话
/// 「写侧跳过关联」写错成「无条件跳过所有关联」也能让本用例全绿。
///
/// 这条形状是真实回归：`JpaPersistExecutor#setValues` 曾把标量与关联都塞进 `SET`，渲染出
/// `set departmentId=?, departmentId=?`，H2/MySQL 直接报重复列名。
@Entity(name = "DualMappedEmployeeEntity")
@Table(name = "dual_mapped_employee")
public class DualMappedEmployeeEntity {

    @Id
    private Long id;

    private String name;

    @Column(name = "departmentId")
    private Long departmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "departmentId", insertable = false, updatable = false)
    private DualMappedDepartmentEntity department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "managerDepartmentId")
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
