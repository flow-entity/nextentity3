package io.github.nextentity.jdbc.support;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Convert(disableConversion = true)`：本轮不实现 `autoApply`，显式关闭转换后按无转换器处理。
@Entity
@Table(name = "disabled_conversion_item")
public class DisabledConversionItem {

    @Id
    private Long id;

    @Convert(converter = ItemStatusConverter.class, disableConversion = true)
    private String payload;

    protected DisabledConversionItem() {}

    public DisabledConversionItem(long id, String payload) {
        this.id = id;
        this.payload = payload;
    }

    public Long id() {
        return id;
    }

    public String payload() {
        return payload;
    }
}
