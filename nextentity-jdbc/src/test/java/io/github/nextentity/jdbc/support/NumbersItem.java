package io.github.nextentity.jdbc.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.BigInteger;

/// 数值 / 布尔列类型样本：包装与原始类型成对出现，用于 SQL 快照与建表往返。
@SuppressWarnings({"PMD.TooManyMethods", "PMD.ExcessiveParameterList"})
@Entity
@Table(name = "numbers_item")
public class NumbersItem {

    @Id
    private Long id;

    private Boolean active;
    private boolean enabled;
    private Double ratio;
    private double ratioPrimitive;
    private Float weight;
    private float weightPrimitive;
    private Short count;
    private short countPrimitive;
    private BigDecimal price;
    private BigInteger serial;

    protected NumbersItem() {}

    public NumbersItem(
            long id,
            Boolean active,
            boolean enabled,
            Double ratio,
            double ratioPrimitive,
            Float weight,
            float weightPrimitive,
            Short count,
            short countPrimitive,
            BigDecimal price,
            BigInteger serial) {
        this.id = id;
        this.active = active;
        this.enabled = enabled;
        this.ratio = ratio;
        this.ratioPrimitive = ratioPrimitive;
        this.weight = weight;
        this.weightPrimitive = weightPrimitive;
        this.count = count;
        this.countPrimitive = countPrimitive;
        this.price = price;
        this.serial = serial;
    }

    public Long id() {
        return id;
    }

    public Boolean active() {
        return active;
    }

    public boolean enabled() {
        return enabled;
    }

    public Double ratio() {
        return ratio;
    }

    public double ratioPrimitive() {
        return ratioPrimitive;
    }

    public Float weight() {
        return weight;
    }

    public float weightPrimitive() {
        return weightPrimitive;
    }

    public Short count() {
        return count;
    }

    public short countPrimitive() {
        return countPrimitive;
    }

    public BigDecimal price() {
        return price;
    }

    public BigInteger serial() {
        return serial;
    }
}
