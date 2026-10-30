package io.github.nextentity.core.spec;

import java.util.Objects;

/// 一次条件删除的完整规格：`DELETE FROM 实体 WHERE 条件`。
///
/// 与 [QuerySpec] 平级——描述写语句，编译器按各自入口消费。
///
/// `where` 为 `null` 表示没有 WHERE（**整表删除**，由调用方显式承担后果），
/// 而不是恒真节点，免得后端多渲染一层 `1=1`。
///
/// @param entityType 删除的实体类型
/// @param where      行过滤条件，可为 null
public record DeleteSpec(Class<?> entityType, Spec where) {

    /// 紧凑构造器：实体类型必填。
    public DeleteSpec {
        Objects.requireNonNull(entityType, "entityType");
    }
}
