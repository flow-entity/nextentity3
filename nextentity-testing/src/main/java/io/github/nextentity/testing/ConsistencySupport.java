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
    /// @return 全新的查询入口：条件不得跨用例累积
    EntityQuery<T> query();

    /// 清空并写入夹具，返回后对 {@link #query()} 可见。
    ///
    /// @param items 固定夹具；返回后必须对新查询可见（已提交或已刷新）
    void seedData(List<T> items);

    /// 构造一个夹具实例（不入库）。
    ///
    /// @param id     主键；同一夹具内唯一
    /// @param name   名称，非 `null`
    /// @param score  数值或 `null`
    /// @param score2 第二列数值或 `null`（列对列比较与算术用）
    /// @param note   文本或 `null`
    /// @param status 枚举状态，非 `null`
    /// @return 未入库的实体实例
    T newItem(long id, String name, Integer score, Integer score2, String note, ItemStatus status);

    /// 主键属性引用。
    ///
    /// @return 方法引用形式的主键：后端实体上必须真实存在
    PathRef<T, Long> idRef();

    /// 名称属性引用。
    ///
    /// @return 方法引用形式的名称：后端实体上必须真实存在
    PathRef.StringRef<T> nameRef();

    /// 数值属性引用。
    ///
    /// @return 方法引用形式的数值：类型为 `Integer`，可参与区间比较
    PathRef.NumberRef<T, Integer> scoreRef();

    /// 第二列数值属性引用（列对列比较与算术用，与 {@link #scoreRef()} 同型）。
    ///
    /// @return 方法引用形式的第二列数值
    PathRef.NumberRef<T, Integer> score2Ref();

    /// 文本属性引用。
    ///
    /// @return 方法引用形式的文本：可参与 `isNull`/字符串算子
    PathRef.StringRef<T> noteRef();

    /// 枚举属性引用。
    ///
    /// @return 方法引用形式的枚举：按枚举值比较，与存储形态无关
    PathRef<T, ItemStatus> statusRef();
}
