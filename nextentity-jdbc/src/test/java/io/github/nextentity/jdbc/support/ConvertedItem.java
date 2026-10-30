package io.github.nextentity.jdbc.support;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Convert` 桥接：枚举列经 `AttributeConverter<ItemStatus, String>` 落成字符串，读写两侧都走转换器。
@Entity
@Table(name = "converted_item")
public class ConvertedItem {

    @Id
    private Long id;

    @Convert(converter = ItemStatusConverter.class)
    private ItemStatus status;

    private String note;

    protected ConvertedItem() {}

    public ConvertedItem(long id, ItemStatus status, String note) {
        this.id = id;
        this.status = status;
        this.note = note;
    }

    public Long id() {
        return id;
    }

    public ItemStatus status() {
        return status;
    }

    public String note() {
        return note;
    }
}
