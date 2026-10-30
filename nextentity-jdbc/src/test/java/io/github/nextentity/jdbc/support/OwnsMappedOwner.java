package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 写关联的持有方：关联目标的主键在 `@MappedSuperclass` 父类上。
@Entity
@Table(name = "owns_mapped_owner")
public class OwnsMappedOwner {

    @Id
    private Long id;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private MappedSubclassOwner owner;

    protected OwnsMappedOwner() {}

    public OwnsMappedOwner(long id, MappedSubclassOwner owner) {
        this.id = id;
        this.owner = owner;
    }

    public Long id() {
        return id;
    }

    public MappedSubclassOwner owner() {
        return owner;
    }
}
