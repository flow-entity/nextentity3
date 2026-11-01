package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// `@Version` + `@Column(insertable = false)`：两后端都必须在元数据期拒绝的映射（见 `JpaVersionInsertableTest`）。
///
/// 版本初值由本层写入（`insert` 写 0，之后的 `update(entity)` 用 `where version = 0` 做乐观锁），
/// 而 Hibernate 遇到 `insertable = false` 会把版本列从 `INSERT` 里摘掉——库内版本停在 `NULL`：
/// 基本类型版本读回直接 `PropertyAccessException`，包装类型则是假的 `OptimisticLockException`。
/// 这个标志在版本列上无法被两后端一致地实现，所以显式拒绝而不是静默产出 `NULL` 版本。
@Entity
@Table(name = "insertable_false_versioned")
public class InsertableFalseVersioned {

    @Id
    private Long id;

    private String name;

    @Version
    @Column(insertable = false)
    private long version;

    public InsertableFalseVersioned() {}

    public InsertableFalseVersioned(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getVersion() {
        return version;
    }
}
