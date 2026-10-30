package io.github.nextentity.jdbc.support;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Convert` 的实体类型与数据库类型不同的实体：`Long` 字段存成 `BIGINT`，转换器按 `BigDecimal` 读。
@Entity
@Table(name = "converted_long_item")
public class ConvertedLongItem {

    @Id
    private Long id;

    @Convert(converter = BigDecimalConverter.class)
    private Long amount;

    protected ConvertedLongItem() {}

    public ConvertedLongItem(long id, Long amount) {
        this.id = id;
        this.amount = amount;
    }

    public Long id() {
        return id;
    }

    public Long amount() {
        return amount;
    }
}
