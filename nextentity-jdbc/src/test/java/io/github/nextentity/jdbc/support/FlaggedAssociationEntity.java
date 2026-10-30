package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// `@JoinColumn(insertable/updatable=false)` 只裁剪写侧列集：`skipped` 不进 INSERT，`locked` 不进 UPDATE SET；
/// 读回仍按完整列集（§7.1 CUT-15）。
@Entity
@Table(name = "flagged_association")
public class FlaggedAssociationEntity {

    @Id
    private Long id;

    private String name;

    @ManyToOne
    @JoinColumn(name = "skipped_id", insertable = false)
    private SnapshotOwner skipped;

    @ManyToOne
    @JoinColumn(name = "locked_id", updatable = false)
    private SnapshotOwner locked;

    protected FlaggedAssociationEntity() {}

    public FlaggedAssociationEntity(long id, String name, SnapshotOwner skipped, SnapshotOwner locked) {
        this.id = id;
        this.name = name;
        this.skipped = skipped;
        this.locked = locked;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public SnapshotOwner skipped() {
        return skipped;
    }

    public SnapshotOwner locked() {
        return locked;
    }
}
