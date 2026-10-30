package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigInteger;

/// AUTO + `BigInteger` 主键：AUTO 按 IDENTITY 落库，生成键经 `getBigDecimal` 还原成 `BigInteger`。
@Entity
@Table(name = "big_integer_generated_item")
public class GeneratedBigIntegerItem {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private BigInteger id;

    private String name;

    protected GeneratedBigIntegerItem() {}

    public GeneratedBigIntegerItem(String name) {
        this.name = name;
    }

    public BigInteger id() {
        return id;
    }

    public String name() {
        return name;
    }
}
