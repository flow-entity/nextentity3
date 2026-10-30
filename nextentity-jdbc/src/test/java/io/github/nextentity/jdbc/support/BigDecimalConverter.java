package io.github.nextentity.jdbc.support;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.math.BigDecimal;

/// `@Convert` 的数据库类型是 `BigDecimal`、实体类型是 `Long`：读列时必须按 `BigDecimal` 收窄再转换。
@Converter
public class BigDecimalConverter implements AttributeConverter<Long, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(Long value) {
        return value == null ? null : new BigDecimal(value);
    }

    @Override
    public Long convertToEntityAttribute(BigDecimal value) {
        return value == null ? null : value.longValueExact();
    }
}
