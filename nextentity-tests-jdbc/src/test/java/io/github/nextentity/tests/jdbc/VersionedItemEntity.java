package io.github.nextentity.tests.jdbc;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// 乐观锁用例的实体：`@Version Long`，insert 写 0、update 带 `where id=? and version=?` 并回写旧值 + 1。
@Entity
@Table(name = "versioned_item")
public class VersionedItemEntity {

    @Id
    private Long id;

    private String name;

    @Version
    private Long version;

    protected VersionedItemEntity() {}

    public VersionedItemEntity(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Long version() {
        return version;
    }

    /// 测试侧模拟「另一个会话先改过」：把内存版本号改掉，下一次 update 必然撞乐观锁。
    public void overwriteVersionForConflict(Long value) {
        this.version = value;
    }
}
