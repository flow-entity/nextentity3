package io.github.nextentity.jdbc.support;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@AttributeOverride` 里的 `insertable` / `updatable`：JPA 语义下覆盖替换整份列映射，写侧必须按覆盖后的标志跳过。
@Entity
@Table(name = "read_only_override_profile")
public class ReadOnlyOverrideProfile {

    @Id
    private Long id;

    @Embedded
    @AttributeOverride(name = "city", column = @Column(name = "home_city", updatable = false))
    @AttributeOverride(name = "street", column = @Column(name = "home_street", insertable = false))
    private EmbeddedAddress address;

    private String label;

    protected ReadOnlyOverrideProfile() {}

    public ReadOnlyOverrideProfile(long id, EmbeddedAddress address, String label) {
        this.id = id;
        this.address = address;
        this.label = label;
    }

    public Long id() {
        return id;
    }

    public EmbeddedAddress address() {
        return address;
    }

    public String label() {
        return label;
    }
}
