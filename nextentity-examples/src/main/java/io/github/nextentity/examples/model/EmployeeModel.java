package io.github.nextentity.examples.model;

/// 接口投影示例（2.2.3 语义）。
///
/// 3.1.0 的 `select(Class)` 不支持接口 / 抽象类目标，接口投影尚未恢复，本文件仅作历史示例保留。
public interface EmployeeModel {

    Long getId();

    String getName();

    Dep getDepartment();

    record Dep(Long id, Boolean unknown, String name) {
    }

}

