package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.List;

/// 同时带单值关联与集合关联：`fetch(...)` 拒绝文案要把两类路径分别列出（USAGE §8.1）。
@Entity
@Table(name = "mixed_association_item")
public class MixedAssociationItem {

    @Id
    private Long id;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private SnapshotOwner owner;

    @OneToMany
    private List<SnapshotOwner> owners;

    protected MixedAssociationItem() {}
}
