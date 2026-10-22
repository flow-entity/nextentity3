package io.github.nextentity.testing;

/// 夹具的状态枚举：验证枚举字段的比较与排序（point-mall 有 51 处 `@Enumerated`）。
public enum ItemStatus {

    /// 新建。
    NEW,

    /// 生效中。
    ACTIVE,

    /// 已结束。
    DONE
}
