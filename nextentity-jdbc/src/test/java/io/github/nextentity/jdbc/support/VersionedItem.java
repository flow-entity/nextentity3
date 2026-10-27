package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// `@Version long`：版本自增与乐观锁冲突用例。
@Entity
@Table(name = "versioned_item")
public class VersionedItem {

    @Id
    private Long id;

    private String name;

    @Version
    private long version;

    protected VersionedItem() {}

    public VersionedItem(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public long version() {
        return version;
    }

    public void version(long version) {
        this.version = version;
    }

    public void name(String name) {
        this.name = name;
    }
}
