package io.github.nextentity.tests.jdbc;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// `@ManyToOne` 关联的写入边界用例：meta 认这个属性，但实体查询的 SELECT 列与 INSERT/UPDATE 的 SET 列
/// 都必须把它过滤掉；写入时关联值非 `null` 按拒绝清单抛 `UnsupportedOperationException`。
@Entity
@Table(name = "managed_association")
public class ManagingAssociationEntity {

    @Id
    private Long id;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private OwnerEntity owner;

    protected ManagingAssociationEntity() {}

    public ManagingAssociationEntity(long id, OwnerEntity owner) {
        this.id = id;
        this.owner = owner;
    }

    public Long id() {
        return id;
    }

    public OwnerEntity owner() {
        return owner;
    }
}
