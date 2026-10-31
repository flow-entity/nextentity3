package io.github.nextentity.jpa.entity;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Embedded` 写侧只读标记夹具：嵌入内部的列带 `@Column(updatable=false)`，条件更新与单实体
/// `update(entity)` 都必须按叶子列判可写，而不是把整块可嵌入对象当成一个可写属性。
@Entity
@Table(name = "embedded_flags")
public class EmbeddedFlags {

    @Id
    private Long id;

    private String label;

    @Embedded
    private LockedAddress address;

    public EmbeddedFlags() {}

    public EmbeddedFlags(Long id, String label, LockedAddress address) {
        this.id = id;
        this.label = label;
        this.address = address;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public LockedAddress getAddress() {
        return address;
    }

    public void setAddress(LockedAddress address) {
        this.address = address;
    }
}
