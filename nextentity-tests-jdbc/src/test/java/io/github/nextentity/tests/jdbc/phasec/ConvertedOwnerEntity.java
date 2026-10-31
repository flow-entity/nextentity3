package io.github.nextentity.tests.jdbc.phasec;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// Phase C 夹具：主键列映射是**非恒等**的（`Long 9` ↔ `"owner-9"`），见 [OwnerIdColumnConverter]。
///
/// 写路径若没复用目标 `@Id` 的列映射，H2 会把 `9L` 隐式转成 `"9"` 落库；后续按默认的
/// `id.toString()` 语义去比就会错位。
@Entity
@Table(name = "converted_owner")
public class ConvertedOwnerEntity {

    @Id
    @Convert(converter = OwnerIdColumnConverter.class)
    private Long id;

    private String name;

    protected ConvertedOwnerEntity() {}

    public ConvertedOwnerEntity(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }
}
