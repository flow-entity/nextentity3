package io.github.nextentity.tests.jpa;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 嵌平投影的被查实体：一个 [PostalAddress] 嵌入（`city` 是一层叶子、`geo.zone` 是两层叶子），
/// 用来证明 `select(Dto.class)` 的 DTO 属性名能按名回找到嵌入列。
///
/// 只用**一个**嵌入组件是刻意的：同名叶子出现在两个组件里时必须报歧义（见
/// [ContactItemEntity] 那一组用例），单组件才能证正向路径。
@Entity(name = "EmbeddedDtoSelectEntity")
@Table(name = "embedded_dto_select")
public class EmbeddedDtoSelectEntity {

    @Id
    private Long id;

    private String name;

    @Embedded
    private PostalAddress address;

    protected EmbeddedDtoSelectEntity() {}

    public EmbeddedDtoSelectEntity(Long id, String name, PostalAddress address) {
        this.id = id;
        this.name = name;
        this.address = address;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public PostalAddress address() {
        return address;
    }
}
