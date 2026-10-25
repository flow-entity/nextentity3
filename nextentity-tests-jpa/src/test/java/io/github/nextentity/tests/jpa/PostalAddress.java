package io.github.nextentity.tests.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;

/// 可嵌入的地址：一层普通列 `city` + 一层再嵌入 `geo`，用来同时覆盖
/// 「单层嵌入」与「嵌入里再嵌一层」两种写侧摊平。
///
/// 列名刻意写成与属性名不同的 `city` / `zone`：`@AttributeOverride` 的语义是「换列名」，
/// 属性名与列名同名时，把「按属性名当列名」的错误实现也蒙对，用例就没有区分力。
@Embeddable
public class PostalAddress {

    @Column(name = "city")
    private String city;

    @Embedded
    private GeoPoint geo;

    public PostalAddress() {}

    public PostalAddress(String city, String zone) {
        this.city = city;
        this.geo = new GeoPoint(zone);
    }

    public String city() {
        return city;
    }

    public GeoPoint geo() {
        return geo;
    }
}
