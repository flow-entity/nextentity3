package io.github.nextentity.core.spec;

/// 比较算子。
///
/// 每个算子声明自己是否需要比较值，构造 `Comparison` 时据此校验，避免出现
/// 「IS NULL 还带值」这类非法节点。
public enum ComparisonOperator {

    /// 等于。
    EQ(true),

    /// 不等于。
    NE(true),

    /// 大于。
    GT(true),

    /// 大于等于。
    GE(true),

    /// 小于。
    LT(true),

    /// 小于等于。
    LE(true),

    /// 模糊匹配。
    LIKE(true),

    /// 模糊不匹配。
    NOT_LIKE(true),

    /// 属于集合。
    IN(true),

    /// 不属于集合。
    NOT_IN(true),

    /// 闭区间。
    BETWEEN(true),

    /// 为空。
    IS_NULL(false),

    /// 不为空。
    IS_NOT_NULL(false);

    private final boolean valueRequired;

    ComparisonOperator(boolean valueRequired) {
        this.valueRequired = valueRequired;
    }

    /// 是否需要比较值。
    ///
    /// @return 需要返回 true
    public boolean valueRequired() {
        return valueRequired;
    }
}
