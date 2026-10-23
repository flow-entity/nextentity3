package io.github.nextentity.tests.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// `@Version` 为非 Integer / Long 类型的实体：JPA 规范允许 `short` / `Short` 作版本类型，
/// Hibernate 也支持（`ShortJavaType` 实现了 `VersionJavaType`），但 `JpaPersistExecutor` 的
/// 版本自增只实现了 Integer / Long 两条分支。这条实体专门用来钉住那个显式拒绝分支。
///
/// 只用于测试：形状与 [VersionedEntity] 一致，只把版本列换成 `short`。
@Entity(name = "ShortVersionedEntity")
@Table(name = "short_versioned")
public class ShortVersionedEntity {

    @Id
    private Long id;

    private String name;

    @Version
    private short version;

    protected ShortVersionedEntity() {}

    public ShortVersionedEntity(long id, String name, short version) {
        this.id = id;
        this.name = name;
        this.version = version;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public short version() {
        return version;
    }
}
