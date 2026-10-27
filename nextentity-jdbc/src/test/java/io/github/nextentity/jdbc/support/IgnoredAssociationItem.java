package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.List;

/// §4.3 #9 的「静默忽略」样本：集合关联不进 `attributes()`，但 `attribute(name)` 仍可解析到它
/// （`joinColumn()` 为 `null`），好让路径渲染层在 `#15 集合关联路径未支持` 处拒绝。
@Entity
@Table(name = "ignored_association_item")
public class IgnoredAssociationItem {

    @Id
    private Long id;

    @OneToMany
    private List<SnapshotOwner> owners;

    protected IgnoredAssociationItem() {}

    public IgnoredAssociationItem(long id) {
        this.id = id;
    }

    public Long id() {
        return id;
    }

    public List<SnapshotOwner> owners() {
        return owners;
    }
}
