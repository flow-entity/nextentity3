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
/// id=1 name=apple     score=10   score2=10   note=null status=NEW
/// id=2 name=banana    score=20   score2=10   note=n1   status=ACTIVE
/// id=3 name=cherry    score=30   score2=null note=n2   status=DONE
/// id=4 name=apple pie score=null score2=5    note=null status=NEW
/// ```
///
/// `score` / `score2` 一列或两列为 `null` 的行（3、4）专门用来钉死 SQL 三值逻辑：
/// 它们既不满足 `a = b` 也不满足 `a <> b`，也不出现在 `between` / `notBetween` 的任何一侧。
///
/// @param <T> 后端实体类型，必须实现 {@link Item}
public abstract class ConsistencyTestBase<T extends Item> {

    protected static final long APPLE_ID = 1L;

    protected static final long BANANA_ID = 2L;

    protected static final long CHERRY_ID = 3L;

    protected static final long APPLE_PIE_ID = 4L;

    protected static final String APPLE_NAME = "apple";

    protected static final String BANANA_NAME = "banana";

    protected static final String CHERRY_NAME = "cherry";

    protected static final String APPLE_PIE_NAME = "apple pie";

    /// 夹具里不存在的名称：用于「无命中」断言。
    protected static final String MISSING_NAME = "zzz";

    /// 夹具文本值（`note` 只在两行非空，用于分组/空值断言）。
    protected static final String NOTE_1 = "n1";

    protected static final String NOTE_2 = "n2";

    /// 每个用例前重建固定夹具（状态分布：apple=NEW, banana=ACTIVE, cherry=DONE, apple pie=NEW）。
    @BeforeEach
    final void seed() {
        ConsistencySupport<T> support = support();
        support.seedData(List.of(
                support.newItem(APPLE_ID, APPLE_NAME, 10, 10, null, ItemStatus.NEW),
                support.newItem(BANANA_ID, BANANA_NAME, 20, 10, NOTE_1, ItemStatus.ACTIVE),
                support.newItem(CHERRY_ID, CHERRY_NAME, 30, null, NOTE_2, ItemStatus.DONE),
                support.newItem(APPLE_PIE_ID, APPLE_PIE_NAME, null, 5, null, ItemStatus.NEW)));
    }

    /// 后端接线（由具体后端提供）。
    ///
    /// @return 一致性能力集：夹具与查询入口都由它提供
    protected abstract ConsistencySupport<T> support();

    /// 全新的查询入口。
    ///
    /// @return 全新的查询入口：条件不得跨用例累积
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

    /// 第二列数值属性引用。
    ///
    /// @return 方法引用形式的第二列数值
    protected final PathRef.NumberRef<T, Integer> score2Ref() {
        return support().score2Ref();
    }

    /// 文本属性引用。
    ///
    /// @return 方法引用形式的文本
    protected final PathRef.StringRef<T> noteRef() {
        return support().noteRef();
    }

    /// 枚举属性引用。
    ///
    /// @return 方法引用形式的枚举
    protected final PathRef<T, ItemStatus> statusRef() {
        return support().statusRef();
    }
}
