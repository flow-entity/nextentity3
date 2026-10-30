package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// `@Version short`：版本类型放宽后的自增分支（`2 → 3` 写回 `short`）。
@Entity
@Table(name = "short_versioned_item")
public class VersionedShortItem {

    @Id
    private Long id;

    private String name;

    @Version
    private short version;

    protected VersionedShortItem() {}

    public VersionedShortItem(long id, String name) {
        this.id = id;
        this.name = name;
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

    public void version(short version) {
        this.version = version;
    }
}
