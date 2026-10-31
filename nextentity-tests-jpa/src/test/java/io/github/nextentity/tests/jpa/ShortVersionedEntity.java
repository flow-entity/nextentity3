package io.github.nextentity.tests.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// `@Version` 为 `short` 的实体：两后端的版本自增都支持 int / long / short 三族，
/// 但到类型上界必须显式拒绝（回绕会让乐观锁永远通过）。这条实体用来钉住 short 的自增
/// 与上界拒绝两个分支。
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
