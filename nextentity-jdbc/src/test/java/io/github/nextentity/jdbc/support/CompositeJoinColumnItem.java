package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 复合外键（`@JoinColumns` 两列）：本层只建模单列外键，元数据期必须显式拒绝——
/// 3.2.0 的 `getAnnotation(JoinColumn.class)` 对容器写法返回 null，会把它静默当成单列映射。
@Entity
@Table(name = "composite_join_column_item")
public class CompositeJoinColumnItem {

    @Id
    private Long id;

    @ManyToOne
    @JoinColumns({@JoinColumn(name = "owner_a"), @JoinColumn(name = "owner_b")})
    private SnapshotOwner owner;

    protected CompositeJoinColumnItem() {}
}
