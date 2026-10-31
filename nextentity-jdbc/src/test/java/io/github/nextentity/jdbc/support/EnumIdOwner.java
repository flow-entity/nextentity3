package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Id` 落在枚举上的关联目标（`@Enumerated(STRING)`：库里存枚举 `name()`）。
///
/// 用于补覆盖：写关联 FK 时必须走目标 `@Id` 的枚举存储形态，与条件写 `关联.id` 的绑定值一致。
@Entity
@Table(name = "enum_id_owner")
public class EnumIdOwner {

    @Id
    @Enumerated(EnumType.STRING)
    private ItemStatus id;

    private String name;

    protected EnumIdOwner() {}

    public EnumIdOwner(ItemStatus id) {
        this.id = id;
    }

    public ItemStatus id() {
        return id;
    }

    public String name() {
        return name;
    }
}
