package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// 除 `@Id` 外只有一列只读 `@Version`：唯一可写列就是版本列，用来钉住
/// 「版本列不受 `@Column(updatable = false)` 跳过」——否则 `SET` 为空只能抛 `UOE`。
@Entity
@Table(name = "read_only_version_only")
public class ReadOnlyVersionOnly {

    @Id
    private Long id;

    @Version
    @Column(name = "version", updatable = false)
    private Integer version;

    public ReadOnlyVersionOnly() {}

    public ReadOnlyVersionOnly(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
