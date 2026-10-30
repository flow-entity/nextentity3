package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/// 关联目标：主键由 `@MappedSuperclass` 父类提供。
@Entity
@Table(name = "mapped_subclass_owner")
public class MappedSubclassOwner extends MappedSuperclassOwnerBase {

    private String name;

    protected MappedSubclassOwner() {}

    public MappedSubclassOwner(long id, String name) {
        super(id);
        this.name = name;
    }

    public String name() {
        return name;
    }
}
