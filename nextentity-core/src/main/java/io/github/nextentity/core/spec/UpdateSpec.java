package io.github.nextentity.core.spec;

import io.github.nextentity.core.util.ImmutableList;
import java.util.List;
import java.util.Objects;

/// 一次条件更新的完整规格：`UPDATE 实体 SET 若干列 WHERE 条件`。
///
/// 与 [QuerySpec] 平级——它描述的是写语句，不是查询；编译器按各自的入口消费，互不继承。
///
/// 缺省约定与 [QuerySpec] 一致：`where` 为 `null` 表示没有 WHERE（**整表更新**，
/// 由调用方显式承担后果），而不是恒真节点，免得后端多渲染一层 `1=1`。
///
/// @param entityType  更新的实体类型
/// @param assignments 赋值列表，至少一条
/// @param where       行过滤条件，可为 null
public record UpdateSpec(Class<?> entityType, List<Assignment> assignments, Spec where) {

    /// 紧凑构造器：必填字段校验 + 不可变拷贝。
    public UpdateSpec {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(assignments, "assignments");
        if (assignments.isEmpty()) {
            throw new IllegalArgumentException("条件更新至少需要一个 set(...)：没有赋值的 UPDATE 没有意义");
        }
        assignments = ImmutableList.copyOf(assignments);
    }
}
