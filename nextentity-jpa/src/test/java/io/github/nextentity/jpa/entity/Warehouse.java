package io.github.nextentity.jpa.entity;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 测试用实体：`location.coordinates.zone` 是**两段嵌入**的三段路径，
/// 中段全不是关联，`JpaPaths.navigate` 必须一路 `get` 到底。
@Entity
@Table(name = "warehouse")
public class Warehouse {

    @Id
    private Long id;

    @Embedded
    private WarehouseLocation location;

    public Warehouse() {}

    public Warehouse(Long id, WarehouseLocation location) {
        this.id = id;
        this.location = location;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public WarehouseLocation getLocation() {
        return location;
    }

    public void setLocation(WarehouseLocation location) {
        this.location = location;
    }
}
