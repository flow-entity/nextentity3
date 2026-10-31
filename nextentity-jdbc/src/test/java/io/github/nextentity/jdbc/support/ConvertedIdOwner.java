package io.github.nextentity.jdbc.support;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Convert` 落在 `@Id` 上的关联目标：主键在库里是 `String` 列。
///
/// 用于钉住「条件改删把 `关联.id` 映射成 FK 列」时必须复用目标 `@Id` 的列映射
/// （converter / storage / databaseType），见 `JdbcEntityAttribute#foreignKey`。
@Entity
@Table(name = "converted_id_owner")
public class ConvertedIdOwner {

    @Id
    @Convert(converter = OwnerIdConverter.class)
    @Column(name = "id")
    private Long id;

    private String name;

    protected ConvertedIdOwner() {}

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }
}
