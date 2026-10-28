package io.github.nextentity.testing;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import java.util.List;

/// 保留字实体的后端接线：与 {@link ConsistencySupport} 同形，但只覆盖关键字标识符这一条链路。
///
/// @param <T> 后端实体类型，必须实现 {@link ReservedWord}
public interface ReservedWordSupport<T extends ReservedWord> {

    /// 全新查询入口（条件不得跨用例累积）。
    ///
    /// @return 查询入口
    EntityQuery<T> query();

    /// 清表并写入夹具。
    ///
    /// @param rows 夹具行
    void seedData(List<T> rows);

    /// 按字段值构造实体（不落库）。
    ///
    /// @param id   主键
    /// @param name 名称
    /// @param desc 关键字列 `desc`
    /// @param key  关键字列 `key`
    /// @return 新实体
    T newReservedWord(long id, String name, String desc, Integer key);

    /// 单行插入。
    ///
    /// @param row 待插入实体
    void insert(T row);

    /// 批量插入。
    ///
    /// @param rows 待插入实体
    void insertAll(Iterable<T> rows);

    /// 按主键更新可写列。
    ///
    /// @param row 带新值的实体（主键标识目标行）
    void update(T row);

    /// 主键属性引用。
    ///
    /// @return 主键引用
    PathRef<T, Long> idRef();

    /// 名称属性引用。
    ///
    /// @return 名称引用
    PathRef.StringRef<T> nameRef();

    /// 关键字列 `desc` 的属性引用。
    ///
    /// @return `desc` 引用
    PathRef.StringRef<T> descRef();

    /// 关键字列 `key` 的属性引用。
    ///
    /// @return `key` 引用
    PathRef.NumberRef<T, Integer> keyRef();
}
