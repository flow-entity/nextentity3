package io.github.nextentity.testing;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import java.util.List;

/// 后端要提供给一致性用例的最小能力集：一个查询入口、一个夹具写入口、四个属性引用。
///
/// 把「后端怎么造查询」收在这一个接口里，是为了让 6 个能力族各自只写一行接线，
/// 而不是每个族都重复 7 个 override。
///
/// 契约：
/// - {@link #seedData(List)} 返回后，写入的数据必须对下一次 {@link #query()} 可见（事务已提交或已刷新）；
/// - {@link #query()} 每次返回全新入口，条件不得跨用例累积；
/// - 属性引用必须是方法引用（后端实体上真实存在的属性）。
///
/// 双列投影必须传 `Path.of(...)`（`Expression`）而不是裸 `PathRef`：point-mall 的 6 处双列投影
/// 调用点全部传 `Path.of(...)`，裸 `PathRef` 不在契约内，因此实现方**不必**为它加兼容分支。
///
/// @param <T> 后端实体类型
public interface ConsistencySupport<T extends Item> {

    /// 全新的查询入口。
    ///
    /// @return 查询入口
    EntityQuery<T> query();

    /// 清空并写入夹具，返回后对 {@link #query()} 可见。
    ///
    /// @param items 固定夹具
    void seedData(List<T> items);

    /// 构造一个夹具实例（不入库）。
    ///
    /// @param id    主键
    /// @param name  名称
    /// @param score 数值或 `null`
    /// @param note  文本或 `null`
    /// @return 实体实例
    T newItem(long id, String name, Integer score, String note);

    /// 主键属性引用。
    ///
    /// @return 方法引用形式的主键
    PathRef<T, Long> idRef();

    /// 名称属性引用。
    ///
    /// @return 方法引用形式的名称
    PathRef.StringRef<T> nameRef();

    /// 数值属性引用。
    ///
    /// @return 方法引用形式的数值
    PathRef.NumberRef<T, Integer> scoreRef();

    /// 文本属性引用。
    ///
    /// @return 方法引用形式的文本
    PathRef.StringRef<T> noteRef();
}
