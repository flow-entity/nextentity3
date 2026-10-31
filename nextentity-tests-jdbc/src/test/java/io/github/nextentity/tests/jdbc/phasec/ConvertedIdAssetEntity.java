package io.github.nextentity.tests.jdbc.phasec;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// Phase C 夹具：关联目标 `ConvertedOwnerEntity` 的主键带 `@Convert`，`owner_id` 列按目标 `@Id`
/// 的列映射应当是 `varchar` + `"owner-9"` 形态，而不是裸数值。
@Entity
@Table(name = "converted_id_asset")
public class ConvertedIdAssetEntity {

    @Id
    private Long id;

    private String name;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private ConvertedOwnerEntity owner;

    protected ConvertedIdAssetEntity() {}

    public ConvertedIdAssetEntity(Long id, String name, ConvertedOwnerEntity owner) {
        this.id = id;
        this.name = name;
        this.owner = owner;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    /// 两段路径的起点：`EntityPath.of(ConvertedIdAssetEntity::owner).get(ConvertedOwnerEntity::id)`。
    public ConvertedOwnerEntity owner() {
        return owner;
    }
}
