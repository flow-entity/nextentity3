package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Enumerated(ORDINAL)`：枚举按序号存储的分支。
@Entity
@Table(name = "ordinal_item")
public class OrdinalItem {

    @Id
    private Long id;

    @Enumerated(EnumType.ORDINAL)
    private ItemStatus status;

    protected OrdinalItem() {}

    public OrdinalItem(long id, ItemStatus status) {
        this.id = id;
        this.status = status;
    }

    public Long id() {
        return id;
    }

    public ItemStatus status() {
        return status;
    }
}
