package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/// 带单值关联的可嵌入类型：`country` 外键可写、`lockedCountry` 是
/// `@JoinColumn(updatable = false)` 的只读外键，`city` 做标量对照。
///
/// 用来验证写侧把「嵌入里的关联」当关联处理（按 `@Id` 写本表外键列、只读外键跳过），
/// 而不是当成一列标量。
@Embeddable
public class ContactInfo {

    @Column(name = "city")
    private String city;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id")
    private Organization country;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locked_country_id", updatable = false)
    private Organization lockedCountry;

    public ContactInfo() {}

    public ContactInfo(String city, Organization country, Organization lockedCountry) {
        this.city = city;
        this.country = country;
        this.lockedCountry = lockedCountry;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public Organization getCountry() {
        return country;
    }

    public void setCountry(Organization country) {
        this.country = country;
    }

    public Organization getLockedCountry() {
        return lockedCountry;
    }

    public void setLockedCountry(Organization lockedCountry) {
        this.lockedCountry = lockedCountry;
    }
}
