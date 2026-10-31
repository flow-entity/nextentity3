package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;

/// 带只读叶子的可嵌入类型：`city` / `detail.code` 可写，`lockedCode` / `detail.secret` 是
/// `@Column(updatable=false)`，用于验证写侧按**叶子列**判可写而不是按外层 `@Embedded` 属性判。
@Embeddable
public class LockedAddress {

    @Column(name = "city")
    private String city;

    @Column(name = "locked_code", updatable = false)
    private String lockedCode;

    @Embedded
    private AddressDetail detail;

    public LockedAddress() {}

    public LockedAddress(String city, String lockedCode, AddressDetail detail) {
        this.city = city;
        this.lockedCode = lockedCode;
        this.detail = detail;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getLockedCode() {
        return lockedCode;
    }

    public void setLockedCode(String lockedCode) {
        this.lockedCode = lockedCode;
    }

    public AddressDetail getDetail() {
        return detail;
    }

    public void setDetail(AddressDetail detail) {
        this.detail = detail;
    }
}
