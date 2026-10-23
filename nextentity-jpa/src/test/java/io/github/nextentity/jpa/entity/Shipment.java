package io.github.nextentity.jpa.entity;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 测试用实体：`address` 是 `@Embedded`（**不是关联**），它的下一段路径必须走 `get` 而不是 `join`。
@Entity
@Table(name = "shipment")
public class Shipment {

    @Id
    private Long id;

    @Embedded
    private ShipmentAddress address;

    public Shipment() {}

    public Shipment(Long id, ShipmentAddress address) {
        this.id = id;
        this.address = address;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ShipmentAddress getAddress() {
        return address;
    }

    public void setAddress(ShipmentAddress address) {
        this.address = address;
    }
}
