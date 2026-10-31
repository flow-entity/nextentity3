package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 关联目标的主键是枚举且用 JPA 默认存储（ORDINAL）：写 FK 时按序号绑定。
@Entity
@Table(name = "ordinal_id_association")
public class OrdinalIdAssociationItem {

    @Id
    private Long id;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private OrdinalIdOwner owner;

    protected OrdinalIdAssociationItem() {}

    public OrdinalIdAssociationItem(Long id, OrdinalIdOwner owner) {
        this.id = id;
        this.owner = owner;
    }

    public Long id() {
        return id;
    }

    public OrdinalIdOwner owner() {
        return owner;
    }
}
