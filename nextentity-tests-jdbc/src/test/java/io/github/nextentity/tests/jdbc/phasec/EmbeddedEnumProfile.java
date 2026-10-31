package io.github.nextentity.tests.jdbc.phasec;

import io.github.nextentity.testing.ItemStatus;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/// Phase C 夹具：`@Embeddable` 里放一个 `@Enumerated(STRING)` 叶子（列名默认 `profile_status`）。
///
/// 值投影 `select(EntityPath.of(...::profile).get(...::status))` 应返回枚举常量，而不是列里的 String。
@Embeddable
public class EmbeddedEnumProfile {

    @Enumerated(EnumType.STRING)
    private ItemStatus status;

    protected EmbeddedEnumProfile() {}

    public EmbeddedEnumProfile(ItemStatus status) {
        this.status = status;
    }

    public ItemStatus status() {
        return status;
    }
}
