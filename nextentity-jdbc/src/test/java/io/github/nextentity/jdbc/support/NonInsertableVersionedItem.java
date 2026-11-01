package io.github.nextentity.jdbc.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// `@Version` + `@Column(insertable=false)`：两后端都在元数据期拒绝的映射（拒绝用例见 `WriteSqlVersionTest`）。
///
/// 版本初值由本层写入（`insert` 写 0，之后 `where version = ?` 做乐观锁），而这个标志会让「谁负责初值」
/// 在两后端分叉：Hibernate 遵守它把版本列摘出 `INSERT`（库内停在 NULL，读回即炸），本层若无视它又与
/// JPA 不一致。既然无法被一致实现，就 fail-fast，而不是各自静默给出不同结果。
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
