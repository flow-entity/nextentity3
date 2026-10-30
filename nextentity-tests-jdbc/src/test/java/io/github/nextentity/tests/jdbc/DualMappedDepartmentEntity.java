package io.github.nextentity.tests.jdbc;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 同列映射夹具的「被引用端」（对照 legacy `Employee` 的 `Department`）。
///
/// 只承载主键与名字：焦点是**同一列被标量字段与关联各映射一次**时 JDBC 写侧怎么办。
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
