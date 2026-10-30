package io.github.nextentity.jdbc.support;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Embedded` + `@AttributeOverride`：嵌入列扁平化成一列，`city` 覆盖成 `town`，`street` 走默认命名
/// `address_street`。
@Entity
@Table(name = "embedded_profile")
public class EmbeddedProfile {

    @Id
    private Long id;

    @Embedded
    @AttributeOverride(name = "city", column = @Column(name = "town"))
    private EmbeddedAddress address;

    private String label;

    protected EmbeddedProfile() {}

    public EmbeddedProfile(long id, EmbeddedAddress address, String label) {
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
