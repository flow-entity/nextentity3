package io.github.nextentity.jpa.entity;

import jakarta.persistence.AssociationOverride;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/// `@Embedded` 里放单值关联的写侧夹具：`contact.country` 外键可写、`contact.lockedCountry` 是
/// `@JoinColumn(updatable=false)` 的只读外键，`billing.country` 的只读性来自实体侧
/// `@AssociationOverride(joinColumns = @JoinColumn(updatable=false))` 的覆盖。
///
/// 只服务写侧「嵌入里的关联按关联处理」的用例：当成标量写会连只读外键一起写，条件更新还会
/// 放行「嵌入属性.关联对象」这种非法 SET 目标。
@Entity
@Table(name = "embedded_association_flags")
public class EmbeddedAssociationFlags {

    @Id
    private Long id;

    private String label;

    @Embedded
    private ContactInfo contact;

    @Embedded
    @AssociationOverride(name = "country", joinColumns = @JoinColumn(name = "billing_country_id", updatable = false))
    private AddressBook billing;

    public EmbeddedAssociationFlags() {}

    public EmbeddedAssociationFlags(Long id, String label, ContactInfo contact, AddressBook billing) {
        this.id = id;
        this.label = label;
        this.contact = contact;
        this.billing = billing;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public ContactInfo getContact() {
        return contact;
    }

    public AddressBook getBilling() {
        return billing;
    }
}
