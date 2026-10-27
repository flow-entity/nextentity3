package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 非生成键实体的「主键为 null」样本（§4.3 #22）：`insert` 在发语句之前就拒绝。
@Entity
@Table(name = "nullable_id_item")
public class NullableIdItem {

    @Id
    private Long id;

    private String name;

    protected NullableIdItem() {}

    public NullableIdItem(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }
}
