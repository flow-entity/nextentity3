package io.github.nextentity.core.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// 集合成员节点：`路径 IN (值...)` / `路径 NOT IN (值...)`。
///
/// 空集合在 builder 层就被折成 {@link Constant}（2.2.3：`IN` 空 → `1=0`，`NOT IN` 空 → `1=1`），
/// 因此本节点拒绝空集合——"空 IN"这种非法形态在构造期即被拒绝，编译器不必再处理。
///
/// @param path    属性路径
/// @param values  值集合，非空
/// @param negated `true` 表示 `NOT IN`
public record In(AttributePath path, List<Object> values, boolean negated) implements Spec {

    /// 紧凑构造器：校验并复制为不可变列表（元素允许为 null，与 2.2.3 的字面量一致）。
    public In {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("空集合必须在 builder 层折成 Constant，不能构造空 IN 节点");
        }
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }
}
