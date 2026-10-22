package io.github.nextentity.testing;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;

/// 能力一致性用例的公共底座：只声明「后端要提供什么」，不负责任何执行细节。
///
/// 契约（后端实现方必须遵守）：
/// - {@link #seedData(List)} 结束后，本用例产生的数据必须对 {@link #query()} **可见**
///   （即事务已提交或已刷新），一致性断言才有意义；
/// - {@link #query()} 每次返回全新的查询入口，条件不得跨用例累积；
/// - 属性引用必须来自方法引用（`Item::name` 这类），四个引用由后端给出名字。
///
/// 固定夹具（每个用例执行前重建），后续断言全部按这份数据手算：
/// ```text
/// id=1 name=apple     score=10    note=null
/// id=2 name=banana    score=20    note=n1
/// id=3 name=cherry    score=30    note=n2
/// id=4 name=apple pie score=null  note=null
/// ```
///
/// @param <T> 后端实体类型，必须实现 {@link Item}
public abstract class ConsistencyTestBase<T extends Item> {

    /// 固定夹具 id。
    protected static final long APPLE_ID = 1L;
    /// 固定夹具 id。
    protected static final long BANANA_ID = 2L;
    /// 固定夹具 id。
    protected static final long CHERRY_ID = 3L;
    /// 固定夹具 id。
    protected static final long APPLE_PIE_ID = 4L;

    /// 固定夹具名称。
    protected static final String APPLE_NAME = "apple";
    /// 固定夹具名称。
    protected static final String BANANA_NAME = "banana";
    /// 固定夹具名称。
    protected static final String CHERRY_NAME = "cherry";
    /// 固定夹具名称。
    protected static final String APPLE_PIE_NAME = "apple pie";
    /// 夹具里不存在的名称：用于「无命中」断言。
    protected static final String MISSING_NAME = "zzz";

    /// 每个用例前重建固定夹具。
    @BeforeEach
    final void seed() {
        seedData(List.of(
                newItem(APPLE_ID, APPLE_NAME, 10, null),
                newItem(BANANA_ID, BANANA_NAME, 20, "n1"),
                newItem(CHERRY_ID, CHERRY_NAME, 30, "n2"),
                newItem(APPLE_PIE_ID, APPLE_PIE_NAME, null, null)));
    }

    /// 全新的查询入口。
    ///
    /// @return 查询入口
    protected abstract EntityQuery<T> query();

    /// 清空并写入夹具；返回后数据必须对 {@link #query()} 可见。
    ///
    /// @param items 固定夹具
    protected abstract void seedData(List<T> items);

    /// 构造一个夹具实例（不入库）。
    ///
    /// @param id    主键
    /// @param name  名称
    /// @param score 数值或 `null`
    /// @param note  文本或 `null`
    /// @return 实体实例
    protected abstract T newItem(long id, String name, Integer score, String note);

    /// 主键属性引用。
    ///
    /// @return 方法引用形式的主键
    protected abstract PathRef<T, Long> idRef();

    /// 名称属性引用。
    ///
    /// @return 方法引用形式的名称
    protected abstract PathRef.StringRef<T> nameRef();

    /// 数值属性引用。
    ///
    /// @return 方法引用形式的数值
    protected abstract PathRef.NumberRef<T, Integer> scoreRef();

    /// 文本属性引用。
    ///
    /// @return 方法引用形式的文本
    protected abstract PathRef.StringRef<T> noteRef();
}
