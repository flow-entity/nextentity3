package io.github.nextentity.jpa.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 歧义夹具：同一个 `@Embeddable` 在实体上出现两次，两处都有 `city` 叶子。
///
/// `select(Dto.class)` 的 `city` 因此无法唯一回找（`home.city` 还是 `office.city`），
/// `JpaEmbeddedPaths` 必须显式拒绝——静默挑一个会让结果看着像数据错。列名用
/// `@AttributeOverrides` 分开，否则 Hibernate 启动时就报重复列。
@Entity
@Table(name = "ambiguous_embedded_holder")
public class AmbiguousEmbeddedHolder {

    @Id
    private Long id;

    @Embedded
    @AttributeOverrides(@AttributeOverride(name = "city", column = @Column(name = "home_city")))
    private ShipmentAddress home;

    @Embedded
    @AttributeOverrides(@AttributeOverride(name = "city", column = @Column(name = "office_city")))
    private ShipmentAddress office;

    public AmbiguousEmbeddedHolder() {}

    public AmbiguousEmbeddedHolder(Long id, ShipmentAddress home, ShipmentAddress office) {
        this.id = id;
        this.home = home;
        this.office = office;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ShipmentAddress getHome() {
        return home;
    }

    public void setHome(ShipmentAddress home) {
        this.home = home;
    }

    public ShipmentAddress getOffice() {
        return office;
    }

    public void setOffice(ShipmentAddress office) {
        this.office = office;
    }
}
