package io.github.nextentity.jdbc.support;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 包内可见的转换器：`JdbcColumns` 在 meta 包里实例化它，必须调用 `setAccessible(true)` 才能无参构造。
@Entity
@Table(name = "package_private_converted_item")
public class PackagePrivateConvertedItem {

    @Id
    private Long id;

    @Convert(converter = HiddenStatusConverter.class)
    private ItemStatus status;

    protected PackagePrivateConvertedItem() {}

    public PackagePrivateConvertedItem(long id, ItemStatus status) {
        this.id = id;
        this.status = status;
    }

    public Long id() {
        return id;
    }

    public ItemStatus status() {
        return status;
    }

    /// 刻意不加 `public`：构造器是包内可见，跨包实例化只能靠反射 `setAccessible`。
    static class HiddenStatusConverter implements AttributeConverter<ItemStatus, String> {

        @Override
        public String convertToDatabaseColumn(ItemStatus status) {
            return status == null ? null : status.name();
        }

        @Override
        public ItemStatus convertToEntityAttribute(String value) {
            return value == null ? null : ItemStatus.valueOf(value);
        }
    }
}
