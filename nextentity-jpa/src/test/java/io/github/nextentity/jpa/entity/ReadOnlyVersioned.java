package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// `@Version` 列带 `@Column(updatable = false)` 的实体：版本自增是持久化层的乐观锁职责，
/// 不该被「只读列不写」的规则吃掉（JDBC 后端同样无条件写版本列）。
@Entity
@Table(name = "read_only_versioned")
public class ReadOnlyVersioned {

    @Id
    private Long id;

    @Column(name = "name")
    private String name;

    @Version
    @Column(name = "version", updatable = false)
    private Integer version;

    public ReadOnlyVersioned() {}

    public ReadOnlyVersioned(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
