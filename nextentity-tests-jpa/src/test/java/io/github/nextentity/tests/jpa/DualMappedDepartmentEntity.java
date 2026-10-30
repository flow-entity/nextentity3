package io.github.nextentity.tests.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 同列映射夹具的「被引用端」（对照 legacy `Employee` 的 `Department`）。
///
/// 只承载主键与名字：这条用例的焦点是**同一列被标量字段与关联各映射一次**时写侧怎么办，
/// 部门表本身不需要更多列。
@Entity(name = "DualMappedDepartmentEntity")
@Table(name = "dual_mapped_department")
public class DualMappedDepartmentEntity {

    @Id
    private Long id;

    private String name;

    protected DualMappedDepartmentEntity() {}

    public DualMappedDepartmentEntity(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }
}
