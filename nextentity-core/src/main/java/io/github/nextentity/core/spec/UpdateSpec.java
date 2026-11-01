package io.github.nextentity.core.spec;

import io.github.nextentity.core.util.ImmutableList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// 一次条件更新的完整规格：`UPDATE 实体 SET 若干列 WHERE 条件`。
///
/// 与 [QuerySpec] 平级——它描述的是写语句，不是查询；编译器按各自的入口消费，互不继承。
///
/// 缺省约定与 [QuerySpec] 一致：`where` 为 `null` 表示没有 WHERE（**整表更新**，
/// 由调用方显式承担后果），而不是恒真节点，免得后端多渲染一层 `1=1`。
///
/// @param entityType  更新的实体类型
/// @param assignments 赋值列表，至少一条，且同一属性路径至多一条
/// @param where       行过滤条件，可为 null
public record UpdateSpec(Class<?> entityType, List<Assignment> assignments, Spec where) {

    /// 紧凑构造器：必填字段校验 + 列去重 + 不可变拷贝。
    ///
    /// 同一列出现两次时 `SET c = ?, c = ?` 没有可移植语义：H2 / PostgreSQL 报
    /// `Duplicate column name` / `multiple assignments to same column`，MySQL 从左到右执行、
    /// 后一次赋值静默生效。交给库裁决等于结果随方言，所以在这里确定性拒绝。
    public UpdateSpec {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(assignments, "assignments");
        if (assignments.isEmpty()) {
            throw new IllegalArgumentException("条件更新至少需要一个 set(...)：没有赋值的 UPDATE 没有意义");
        }
        Set<AttributePath> distinct = new HashSet<>();
        for (Assignment assignment : assignments) {
            if (!distinct.add(assignment.path())) {
                throw new IllegalArgumentException("同一列被 set(...) 了多次: " + entityType.getName() + "."
                        + String.join(".", assignment.path().names()) + "（一条 UPDATE 的 SET 列表里每列只能出现一次）");
            }
        }
        assignments = ImmutableList.copyOf(assignments);
    }
}
