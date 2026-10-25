package io.github.nextentity.tests.jpa;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@AttributeOverride` 的被查实体：同一个可嵌入类型（[PostalAddress]）在同一个实体上出现**两次**，
/// 各自覆盖到不同列（`home_city` / `home_zone` 与 `office_city` / `office_zone`）。
///
/// 这是 `@AttributeOverride` 唯一真正有区分力的用法：同一个 `@Embeddable` 出现一次时，
/// 「覆盖没生效」和「覆盖生效」在结果上分不出来（默认列名本来就只有一处）；出现两次且不覆盖，
/// Hibernate 会直接报重复列映射——所以「两次、不同列」是这条能力的**最小可证伪夹具**。
///
/// 此前非遗留用例里 `@AttributeOverride` 零命中，这一维度只有 2.2.3 移植模块的
/// `AttributeOverrideCrudIntegrationTest` 作证，而该模块按计划会被移除。
///
/// 列名与属性名不同（`city` ≠ `home_city`）是刻意的：若框架某处拿属性名当列名拼 SQL，
/// 覆盖成不同名才会当场报错，同名则静默通过。
@Entity(name = "ContactItemEntity")
@Table(name = "contact_item")
public class ContactItemEntity {

    @Id
    private Long id;

    private String name;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "city", column = @Column(name = "home_city")),
        @AttributeOverride(name = "geo.zone", column = @Column(name = "home_zone"))
    })
    private PostalAddress home;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "city", column = @Column(name = "office_city")),
        @AttributeOverride(name = "geo.zone", column = @Column(name = "office_zone"))
    })
    private PostalAddress office;

    protected ContactItemEntity() {}

    public ContactItemEntity(Long id, String name, PostalAddress home, PostalAddress office) {
        this.id = id;
        this.name = name;
        this.home = home;
        this.office = office;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public PostalAddress home() {
        return home;
    }

    public PostalAddress office() {
        return office;
    }
}
