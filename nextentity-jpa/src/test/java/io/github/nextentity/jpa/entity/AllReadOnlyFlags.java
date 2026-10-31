package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 除主键外**没有任何可写列**的夹具：两个标量列都带 `@Column(updatable=false)`，且没有 `@Version`。
///
/// 写侧把只读列全部跳过之后 `SET` 子句会变空——Hibernate 会对空赋值直接抛
/// `IllegalArgumentException("No assignments specified as part of UPDATE criteria")`。
/// 本库契约要求与 JDBC 后端同口径显式拒（`UnsupportedOperationException`），见 `JpaPersistNoWritableColumnTest`。
@Entity
@Table(name = "all_read_only_flags")
public class AllReadOnlyFlags {

    @Id
    private Long id;

    @Column(name = "state", updatable = false)
    private String state;

    @Column(name = "memo", updatable = false)
    private String memo;

    public AllReadOnlyFlags() {}

    public AllReadOnlyFlags(Long id, String state, String memo) {
        this.id = id;
        this.state = state;
        this.memo = memo;
    }

    public Long getId() {
        return id;
    }

    public String getState() {
        return state;
    }

    public String getMemo() {
        return memo;
    }
}
