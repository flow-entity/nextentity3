package io.github.nextentity.jdbc.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// `@Version` + `@Column(insertable=false)`：版本列由本层初始化（写 0），`insertable` 只约束业务列。
///
/// 与 `@Version @Column(updatable=false)` 同口径——版本自增/初始化是乐观锁的职责，
/// 跳过版本列会让库内版本永远停在 NULL，之后的 `update(entity)` 每次 `where version=?` 都命中 0 行。
@Entity
@Table(name = "non_insertable_versioned_item")
public class NonInsertableVersionedItem {

    @Id
    private Long id;

    private String name;

    @Version
    @Column(insertable = false)
    private long version;

    protected NonInsertableVersionedItem() {}

    public NonInsertableVersionedItem(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public long version() {
        return version;
    }

    public void version(long version) {
        this.version = version;
    }
}
