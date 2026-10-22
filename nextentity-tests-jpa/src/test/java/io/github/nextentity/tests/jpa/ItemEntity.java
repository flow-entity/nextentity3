package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.Item;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 一致性用例的 JPA 侧实体：实现 `nextentity-testing` 的夹具接口，形状与固定夹具一致。
///
/// 只用于测试：字段刻意保持最少（一个主键、一个字符串、一个可为空的数值、一个可为空的文本），
/// 覆盖比较/区间/空值/排序/投影所需的属性面。
@Entity(name = "ItemEntity")
@Table(name = "item")
public class ItemEntity implements Item {

    @Id
    private Long id;

    private String name;

    private Integer score;

    private String note;

    protected ItemEntity() {}

    public ItemEntity(long id, String name, Integer score, String note) {
        this.id = id;
        this.name = name;
        this.score = score;
        this.note = note;
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
}
