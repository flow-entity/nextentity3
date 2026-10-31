package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 关联目标的主键带 `@Convert`：写入 / 过滤 FK 时必须走目标 `@Id` 的列映射。
@Entity
@Table(name = "converted_id_association")
public class ConvertedIdAssociationItem {

    @Id
    private Long id;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private ConvertedIdOwner owner;

    protected ConvertedIdAssociationItem() {}

    public ConvertedIdAssociationItem(Long id, ConvertedIdOwner owner) {
        this.id = id;
        this.owner = owner;
    }

    public Long id() {
        return id;
    }

    public ConvertedIdOwner owner() {
        return owner;
    }
}
