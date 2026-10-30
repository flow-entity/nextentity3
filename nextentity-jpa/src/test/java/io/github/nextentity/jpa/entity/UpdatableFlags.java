package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/// 写侧只读标记夹具：`@Column(updatable=false)` 的标量列与 `@JoinColumn(updatable=false)` 的
/// 外键列各一个，另有对应的可更新列做正向对照。
///
/// 只服务条件更新的 fail-fast 用例：`lockedName` / `lockedOwner` 出现在 `SET` 里必须被拒，
/// 且文案与 JDBC 后端逐字一致。
@Entity
@Table(name = "updatable_flags")
public class UpdatableFlags {

    @Id
    private Long id;

    private String name;

    @Column(name = "locked_name", updatable = false)
    private String lockedName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private Organization owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locked_owner_id", updatable = false)
    private Organization lockedOwner;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locked_seal_id", updatable = false)
    private Seal lockedSeal;

    public UpdatableFlags() {}

    public UpdatableFlags(
            Long id, String name, String lockedName, Organization owner, Organization lockedOwner, Seal lockedSeal) {
        this.id = id;
        this.name = name;
        this.lockedName = lockedName;
        this.owner = owner;
        this.lockedOwner = lockedOwner;
        this.lockedSeal = lockedSeal;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLockedName() {
        return lockedName;
    }

    public Organization getOwner() {
        return owner;
    }

    public Organization getLockedOwner() {
        return lockedOwner;
    }

    public Seal getLockedSeal() {
        return lockedSeal;
    }
}
