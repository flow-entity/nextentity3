package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;

/// 中间那层嵌入：`location.coordinates.zone` 的 `location.coordinates` 两段都必须是 `get`。
@Embeddable
public class WarehouseLocation {

    @Column(name = "region")
    private String region;

    @Embedded
    private GeoCoordinates coordinates;

    public WarehouseLocation() {}

    public WarehouseLocation(String region, GeoCoordinates coordinates) {
        this.region = region;
        this.coordinates = coordinates;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public GeoCoordinates getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(GeoCoordinates coordinates) {
        this.coordinates = coordinates;
    }
}
