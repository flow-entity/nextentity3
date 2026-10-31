package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 关联目标的主键是枚举（`@Enumerated(STRING)`）：写 FK 时必须按目标 `@Id` 的存储形态绑定。
@Entity
@Table(name = "enum_id_association")
public class EnumIdAssociationItem {

    @Id
    private Long id;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private EnumIdOwner owner;

    protected EnumIdAssociationItem() {}

    public EnumIdAssociationItem(Long id, EnumIdOwner owner) {
        this.id = id;
        this.owner = owner;
    }

    public Long id() {
        return id;
    }

    public EnumIdOwner owner() {
        return owner;
    }
}
