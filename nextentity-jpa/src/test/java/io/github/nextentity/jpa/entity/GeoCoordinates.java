package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/// 三层嵌入路径的最内层：给 `location.coordinates.zone` 提供第三段。
///
/// 两段嵌入（嵌入里再嵌一层）是 `JpaPaths.navigate` 递归里唯一还没被夹具走过的地方：
/// 单层嵌入的第二段由 `get` 取，得到的不是 `From`；再往下走一段时若把中段当关联去
/// `join`，就会在非 `From` 的路径上失败——这正是 `compile/JpaPaths.java:62` 那一行
/// 关联判定的作用。
@Embeddable
public class GeoCoordinates {

    @Column(name = "zone")
    private String zone;

    public GeoCoordinates() {}

    public GeoCoordinates(String zone) {
        this.zone = zone;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }
}
