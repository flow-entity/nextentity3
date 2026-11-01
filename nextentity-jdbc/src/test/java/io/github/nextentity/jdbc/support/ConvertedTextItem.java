package io.github.nextentity.jdbc.support;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Convert` 落在 String 列上（实体类型与数据库类型都是 String）。
///
/// 用于钉住「字符串函数结果不是列本身」：`length(col)` 的结果是整数、`substring(col, …)` 的结果是
/// 库内存储形态，绑定时都不该再套列自己的转换器。
@Entity
@Table(name = "converted_text_item")
public class ConvertedTextItem {

    @Id
    private Long id;

    @Convert(converter = PrefixConverter.class)
    private String code;

    protected ConvertedTextItem() {}

    public ConvertedTextItem(long id, String code) {
        this.id = id;
        this.code = code;
    }

    public Long id() {
        return id;
    }

    public String code() {
        return code;
    }

    /// 双向带前缀：字面量若被误按列的转换器转换，会得到 `p:3` 而不是 `3`。
    static class PrefixConverter implements AttributeConverter<String, String> {

        @Override
        public String convertToDatabaseColumn(String value) {
            return value == null ? null : "p:" + value;
        }

        @Override
        public String convertToEntityAttribute(String value) {
            return value == null ? null : value.substring(2);
        }
    }
}
