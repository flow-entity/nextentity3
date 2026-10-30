package io.github.nextentity.jdbc.support;

import jakarta.persistence.Embeddable;

/// 嵌入类型样本：两个标量列，列名由外层 `@Embedded` 字段名拼默认命名（`address_city` / `address_street`），
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
