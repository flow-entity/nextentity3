package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.PathRef;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Convert` 用例的实体：`payload` 落库形态与内存形态不同（point-mall 有 20 处 `@Convert`）。
@Entity(name = "ConvertedEntity")
@Table(name = "converted")
public class ConvertedEntity {

    @Id
    private Long id;

    @Convert(converter = PayloadConverter.class)
    private String payload;

    protected ConvertedEntity() {}

    public ConvertedEntity(long id, String payload) {
        this.id = id;
        this.payload = payload;
    }

    /// 主键引用。
    ///
    /// @return 主键引用
    public static PathRef<ConvertedEntity, Long> idRef() {
        return ConvertedEntity::id;
    }

    /// 转换字段引用。
    ///
    /// @return 字段引用
    public static PathRef<ConvertedEntity, String> payloadRef() {
        return ConvertedEntity::payload;
    }

    public Long id() {
        return id;
    }

    public String payload() {
        return payload;
    }
}
