package io.github.nextentity.jpa.entity;

import jakarta.persistence.Embeddable;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;

/// 关联外键列名与可写性全部由实体侧 `@AssociationOverride` 决定的可嵌入类型：这里刻意不写
/// `@JoinColumn`，用来验证写侧判定看的是覆盖后的生效外键列。
@Embeddable
public class AddressBook {

    @ManyToOne(fetch = FetchType.LAZY)
    private Organization country;

    public AddressBook() {}

    public AddressBook(Organization country) {
        this.country = country;
    }

    public Organization getCountry() {
        return country;
    }

    public void setCountry(Organization country) {
        this.country = country;
    }
}
