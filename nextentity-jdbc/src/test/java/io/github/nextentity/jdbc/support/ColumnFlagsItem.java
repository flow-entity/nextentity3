package io.github.nextentity.jdbc.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Column(insertable = false)` / `@Column(updatable = false)`：写侧跳过该列，读侧照常。
@Entity
@Table(name = "column_flags_item")
public class ColumnFlagsItem {

    @Id
    private Long id;

    @Column(updatable = false)
    private String name;

    @Column(insertable = false)
    private String createdBy;

    protected ColumnFlagsItem() {}

    public ColumnFlagsItem(long id, String name, String createdBy) {
        this.id = id;
        this.name = name;
        this.createdBy = createdBy;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String createdBy() {
        return createdBy;
    }
}
