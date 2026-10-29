package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// `@Version Integer`：版本类型分支（回写 `Integer`）。
@Entity
@Table(name = "integer_versioned_item")
public class IntegerVersionedItem {

    @Id
    private Long id;

    private String name;

    @Version
    private Integer version;

    protected IntegerVersionedItem() {}

    public IntegerVersionedItem(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Integer version() {
        return version;
    }
}
