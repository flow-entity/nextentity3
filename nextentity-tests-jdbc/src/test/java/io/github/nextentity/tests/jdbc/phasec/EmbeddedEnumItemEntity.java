package io.github.nextentity.tests.jdbc.phasec;

import io.github.nextentity.testing.ItemStatus;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// Phase C 夹具：同时有顶层 `@Enumerated(STRING)` 列与 `@Embedded` 里的同类型枚举叶子，
/// 用来区分「顶层枚举投影正确」与「嵌入叶子投影丢类型」。
@Entity
@Table(name = "embedded_enum_item")
public class EmbeddedEnumItemEntity {

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    private ItemStatus state;

    @Embedded
    private EmbeddedEnumProfile profile;

    protected EmbeddedEnumItemEntity() {}

    public EmbeddedEnumItemEntity(Long id, ItemStatus state, EmbeddedEnumProfile profile) {
        this.id = id;
        this.state = state;
        this.profile = profile;
    }

    public Long id() {
        return id;
    }

    public ItemStatus state() {
        return state;
    }

    public EmbeddedEnumProfile profile() {
        return profile;
    }
}
