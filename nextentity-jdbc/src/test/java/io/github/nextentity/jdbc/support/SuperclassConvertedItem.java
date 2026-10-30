package io.github.nextentity.jdbc.support;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Convert` 的父类继承形态：转换器经父类间接实现 `AttributeConverter<ItemStatus, String>`，
/// 数据库类型必须沿父类泛型声明解析。
@Entity
@Table(name = "superclass_converted_item")
public class SuperclassConvertedItem {

    @Id
    private Long id;

    @Convert(converter = DerivedStatusConverter.class)
    private ItemStatus status;

    protected SuperclassConvertedItem() {}

    public SuperclassConvertedItem(long id, ItemStatus status) {
        this.id = id;
        this.status = status;
    }

    public Long id() {
        return id;
    }

    public ItemStatus status() {
        return status;
    }

    /// 直接在接口上给出类型实参的父类。
    public static class BaseStatusConverter implements AttributeConverter<ItemStatus, String> {

        @Override
        public String convertToDatabaseColumn(ItemStatus status) {
            return status == null ? null : status.name();
        }

        @Override
        public ItemStatus convertToEntityAttribute(String value) {
            return value == null ? null : ItemStatus.valueOf(value);
        }
    }

    /// 自身不声明接口，数据库类型只能从父类找。
    public static final class DerivedStatusConverter extends BaseStatusConverter {}
}
