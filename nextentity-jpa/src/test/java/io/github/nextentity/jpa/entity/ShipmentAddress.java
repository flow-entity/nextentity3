package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/// 测试用可嵌入类型：给 {@link Shipment} 提供 `address.city` 这种两段路径。
@Embeddable
public class ShipmentAddress {

    @Column(name = "city")
    private String city;

    public ShipmentAddress() {}

    public ShipmentAddress(String city) {
        this.city = city;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }
}
