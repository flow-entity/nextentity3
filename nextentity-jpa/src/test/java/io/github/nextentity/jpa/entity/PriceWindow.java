package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/// 测试用区间实体：每行自带一个值列与一组区间边界列，用来表达 `value BETWEEN lower AND upper`。
///
/// 区间两端都是列，正是公开 api `between(Expression, Expression)` 的形态；夹具把"命中"与
/// "不命中"混在一张表里，因此区间条件失效时会立刻表现为行数变多。
@Entity
@Table(name = "price_window")
public class PriceWindow {

    @Id
    private Long id;

    @Column(name = "price")
    private BigDecimal value;

    @Column(name = "lower_price")
    private BigDecimal lower;

    @Column(name = "upper_price")
    private BigDecimal upper;

    public PriceWindow() {}

    public PriceWindow(Long id, String value, String lower, String upper) {
        this(id, new BigDecimal(value), new BigDecimal(lower), new BigDecimal(upper));
    }

    public PriceWindow(Long id, BigDecimal value, BigDecimal lower, BigDecimal upper) {
        this.id = id;
        this.value = value;
        this.lower = lower;
        this.upper = upper;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public BigDecimal getLower() {
        return lower;
    }

    public void setLower(BigDecimal lower) {
        this.lower = lower;
    }

    public BigDecimal getUpper() {
        return upper;
    }

    public void setUpper(BigDecimal upper) {
        this.upper = upper;
    }
}
