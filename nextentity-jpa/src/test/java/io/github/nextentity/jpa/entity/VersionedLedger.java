package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;

/// 测试用台账实体：`@Version` 是 **Long**（`JpaPersistExecutor.nextVersion` 的 Long 分支），
/// 另带一个集合关联（`JpaMeta.association` 的 `PluralAttribute` 分支）。
///
/// 与 `ShopOrder` 的分工：那个的版本列是 `int`，只覆盖 Integer 分支；Long 分支与集合关联
/// 在 point-mall 里没有调用点，但都在生产代码里占一条分支，所以单独建实体钉住。
@Entity
@Table(name = "versioned_ledger")
public class VersionedLedger {

    @Id
    private Long id;

    @Column(name = "name")
    private String name;

    @Version
    private Long version;

    @OneToMany(mappedBy = "ledger", fetch = FetchType.LAZY)
    private List<VersionedEntry> entries = new ArrayList<>();

    public VersionedLedger() {}

    public VersionedLedger(Long id, String name, Long version) {
        this.id = id;
        this.name = name;
        this.version = version;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public List<VersionedEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<VersionedEntry> entries) {
        this.entries = entries;
    }
}
