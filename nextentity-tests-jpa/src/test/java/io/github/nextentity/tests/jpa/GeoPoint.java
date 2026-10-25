package io.github.nextentity.tests.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/// 第二层嵌入：给 `PostalAddress` 提供 `geo.zone` 这一段的叶子列。
@Embeddable
public class GeoPoint {

    @Column(name = "zone")
    private String zone;

    public GeoPoint() {}

    public GeoPoint(String zone) {
        this.zone = zone;
    }

    public String zone() {
        return zone;
    }
}
