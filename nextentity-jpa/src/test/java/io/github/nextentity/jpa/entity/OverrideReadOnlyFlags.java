package io.github.nextentity.jpa.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@AttributeOverride` 覆盖出来的只读列夹具：`home.city` 被覆盖成 `home_city` 并标
/// `updatable=false`，`home.zone` 覆盖成可写的 `home_zone` 做正向对照。
///
/// 只服务写侧「按覆盖后的生效列判可写」的用例：`@Embeddable` 里的字段本身没有列注解，
/// 可写性只存在于实体声明的覆盖上，判定若只看 `@Embeddable` 内部注解就会把 `home_city` 写进
/// `SET`（条件更新则完全放行）。
@Entity
@Table(name = "override_readonly_flags")
public class OverrideReadOnlyFlags {

    @Id
    private Long id;

    private String label;

    @Embedded
    @AttributeOverride(name = "city", column = @Column(name = "home_city", updatable = false))
    @AttributeOverride(name = "zone", column = @Column(name = "home_zone"))
    private HomeAddress home;

    public OverrideReadOnlyFlags() {}

    public OverrideReadOnlyFlags(Long id, String label, HomeAddress home) {
        this.id = id;
        this.label = label;
        this.home = home;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public HomeAddress getHome() {
        return home;
    }

    public void setHome(HomeAddress home) {
        this.home = home;
    }
}
