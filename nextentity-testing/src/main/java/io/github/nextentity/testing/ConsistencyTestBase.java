package io.github.nextentity.testing;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;

/// 能力一致性用例的公共底座：固定夹具 + 把后端能力转成断言可用的入口。
///
/// 子类只需给一个 {@link ConsistencySupport}（后端接线），本类负责建夹具与转发查询入口。
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

    /// 夹具文本值（`note` 只在两行非空，用于分组/空值断言）。
    protected static final String NOTE_1 = "n1";
    /// 夹具文本值。
    protected static final String NOTE_2 = "n2";

    /// 每个用例前重建固定夹具。
    @BeforeEach
    final void seed() {
        ConsistencySupport<T> support = support();
        support.seedData(List.of(
                support.newItem(APPLE_ID, APPLE_NAME, 10, null),
                support.newItem(BANANA_ID, BANANA_NAME, 20, NOTE_1),
                support.newItem(CHERRY_ID, CHERRY_NAME, 30, NOTE_2),
                support.newItem(APPLE_PIE_ID, APPLE_PIE_NAME, null, null)));
    }

    /// 后端接线（由具体后端提供）。
    ///
    /// @return 一致性能力集
    protected abstract ConsistencySupport<T> support();

    /// 全新的查询入口。
    ///
    /// @return 查询入口
    protected final EntityQuery<T> query() {
        return support().query();
    }

    /// 主键属性引用。
    ///
    /// @return 方法引用形式的主键
    protected final PathRef<T, Long> idRef() {
        return support().idRef();
    }

    /// 名称属性引用。
    ///
    /// @return 方法引用形式的名称
    protected final PathRef.StringRef<T> nameRef() {
        return support().nameRef();
    }

    /// 数值属性引用。
    ///
    /// @return 方法引用形式的数值
    protected final PathRef.NumberRef<T, Integer> scoreRef() {
        return support().scoreRef();
    }

    /// 文本属性引用。
    ///
    /// @return 方法引用形式的文本
    protected final PathRef.StringRef<T> noteRef() {
        return support().noteRef();
    }
}
