package io.github.nextentity.jpa.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 关联列没有显式 `@JoinColumn` 的最小载体：外键列名交给 JPA 默认命名推断。
///
/// 存在的理由是钉住「缺 `@JoinColumn` 的关联按可写处理」这条缺省口径：没有注解不等于只读，
/// 否则条件更新会把合法的外键写入静默拒掉。
@Entity
@Table(name = "unannotated_link")
public class UnannotatedLink {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Organization owner;

    public UnannotatedLink() {}

    public UnannotatedLink(Long id, Organization owner) {
        this.id = id;
        this.owner = owner;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Organization getOwner() {
        return owner;
    }

    public void setOwner(Organization owner) {
        this.owner = owner;
    }
}
