package io.github.nextentity.tests.jpa.support;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/// 抓取 Hibernate 真正下发的 SQL，供快照断言使用。
///
/// 同一个实例会被所有 Session 共享，所以按线程隔离记账：测试并行时互不污染。
public final class SqlRecorder implements StatementInspector {

    private final ThreadLocal<List<String>> recorded = ThreadLocal.withInitial(ArrayList::new);

    @Override
    public String inspect(String sql) {
        recorded.get().add(sql);
        return sql;
    }

    /// 取走本线程已抓到的 SQL（按执行顺序），并清空账本。
    public List<String> drain() {
        List<String> sql = List.copyOf(recorded.get());
        recorded.get().clear();
        return sql;
    }

    /// 清空本线程已抓到的 SQL。
    public void clear() {
        recorded.get().clear();
    }
}
