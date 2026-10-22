package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.Item;
import io.github.nextentity.testing.ItemStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 一致性用例的 JPA 侧实体：实现 `nextentity-testing` 的夹具接口，形状与固定夹具一致。
///
/// 只用于测试：字段刻意保持最少（主键、字符串、可为空的数值、可为空的文本、枚举），
/// 覆盖比较/区间/空值/排序/投影/枚举所需的属性面。枚举用 `EnumType.STRING`，
/// 与 point-mall 的 50/51 处映射一致。
@Entity(name = "ItemEntity")
@Table(name = "item")
public class ItemEntity implements Item {

    @Id
    private Long id;

    private String name;

    private Integer score;

    private String note;

    @Enumerated(EnumType.STRING)
    private ItemStatus status;

    protected ItemEntity() {}

    public ItemEntity(long id, String name, Integer score, String note, ItemStatus status) {
        this.id = id;
        this.name = name;
        this.score = score;
        this.note = note;
        this.status = status;
    }

    @Override
    public Long id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Integer score() {
        return score;
    }

    @Override
    public String note() {
        return note;
    }

    @Override
    public ItemStatus status() {
        return status;
    }
}
