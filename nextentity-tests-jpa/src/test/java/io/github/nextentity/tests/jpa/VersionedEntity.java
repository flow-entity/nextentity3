package io.github.nextentity.tests.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// `@Version` 用例的实体：point-mall 有 24 个实体带 `@Version`（`AppRepositoryImpl.update/updateAll` 要回写版本号）。
@Entity(name = "VersionedEntity")
@Table(name = "versioned")
public class VersionedEntity {

    @Id
    private Long id;

    private String name;

    @Version
    private long version;

    protected VersionedEntity() {}

    public VersionedEntity(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public VersionedEntity(long id, String name, long version) {
        this.id = id;
        this.name = name;
        this.version = version;
    }

    public Long idValue() {
        return id;
    }

    public String name() {
        return name;
    }

    public long version() {
        return version;
    }
}
