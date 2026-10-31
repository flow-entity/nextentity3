package io.github.nextentity.jpa.entity;

import jakarta.persistence.Embeddable;

/// 不带任何列注解的可嵌入类型：列名与可写性全部由实体侧的 `@AttributeOverride` 决定，
/// 用来验证写侧判定看的是**覆盖后的生效列**而不是 `@Embeddable` 里的原始字段。
@Embeddable
public class HomeAddress {

    private String city;

    private String zone;

    public HomeAddress() {}

    public HomeAddress(String city, String zone) {
        this.city = city;
        this.zone = zone;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }
}
