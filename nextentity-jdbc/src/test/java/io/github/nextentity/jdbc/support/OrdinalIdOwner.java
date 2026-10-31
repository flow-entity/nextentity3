package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Id` 落在枚举上且用 JPA 默认存储（ORDINAL：库里存序号）。
///
/// 用于补覆盖：写关联 FK 时必须按目标 `@Id` 的序号形态绑定。
@Entity
@Table(name = "ordinal_id_owner")
public class OrdinalIdOwner {

    @Id
    private ItemStatus id;

    private String name;

    protected OrdinalIdOwner() {}

    public OrdinalIdOwner(ItemStatus id) {
        this.id = id;
    }

    public ItemStatus id() {
        return id;
    }

    public String name() {
        return name;
    }
}
