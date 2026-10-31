package io.github.nextentity.jdbc.support;

import jakarta.persistence.Embeddable;

/// 嵌入类型样本：两个标量列，列名默认取叶子字段名（`city` / `street`，与 Hibernate/JPA 一致），
/// `@AttributeOverride` 可在外层覆盖单个列名。
@Embeddable
public class EmbeddedAddress {

    private String city;

    private String street;

    protected EmbeddedAddress() {}

    public EmbeddedAddress(String city, String street) {
        this.city = city;
        this.street = street;
    }

    public String city() {
        return city;
    }

    public String street() {
        return street;
    }
}
