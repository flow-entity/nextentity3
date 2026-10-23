package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// `VersionedLedger` 的子行：给台账提供「集合关联 + 单值关联」两种属性形态。
@Entity
@Table(name = "versioned_entry")
public class VersionedEntry {

    @Id
    private Long id;

    @Column(name = "label")
    private String label;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ledger_id")
    private VersionedLedger ledger;

    public VersionedEntry() {}

    public VersionedEntry(Long id, String label, VersionedLedger ledger) {
        this.id = id;
        this.label = label;
        this.ledger = ledger;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public VersionedLedger getLedger() {
        return ledger;
    }

    public void setLedger(VersionedLedger ledger) {
        this.ledger = ledger;
    }
}
