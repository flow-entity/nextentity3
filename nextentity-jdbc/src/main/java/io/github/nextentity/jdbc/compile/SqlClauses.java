package io.github.nextentity.jdbc.compile;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jdbc.dialect.LockClause;
import java.util.ArrayList;
import java.util.List;

/// 除 SELECT 列表以外的子句渲染：`where / group by / having / order by / limit offset / for update`。
///
/// 每个方法返回「带前导空格的子句」或空串，由 `SqlRenderer` 顺序拼接；窗口与锁片段由方言提供。
final class SqlClauses {

    private static final LockClause NO_LOCK = new LockClause("", "");

    private final SqlRenderer renderer;

    SqlClauses(SqlRenderer renderer) {
        this.renderer = renderer;
    }

    String where(Spec where) {
        return where == null ? "" : " where " + new SqlPredicates(renderer).render(where);
    }

    String groupBy(List<AttributePath> groupBy) {
        if (groupBy.isEmpty()) {
            return "";
        }
        List<String> columns = new ArrayList<>();
        for (AttributePath path : groupBy) {
            columns.add(renderer.paths().resolve(path).expression());
        }
        return " group by " + String.join(", ", columns);
    }

    String having(Spec having) {
        return having == null ? "" : " having (" + new SqlPredicates(renderer).render(having) + ")";
    }

    String orderBy(List<Ordering> orderBy) {
        if (orderBy.isEmpty()) {
            return "";
        }
        List<String> items = new ArrayList<>();
        for (Ordering ordering : orderBy) {
            items.add(renderer.paths().resolve(ordering.path()).expression()
                    + (ordering.direction() == Ordering.Direction.ASC ? " asc" : " desc"));
        }
        return " order by " + String.join(", ", items);
    }

    String window(Window window) {
        return window == null ? "" : renderer.window(false);
    }

    LockClause lock(QuerySpec spec) {
        LockMode lock = spec.lock();
        if (lock == null) {
            return NO_LOCK;
        }
        if (lock != LockMode.PESSIMISTIC_WRITE) {
            throw new UnsupportedOperationException("JDBC 后端只支持 PESSIMISTIC_WRITE: " + lock);
        }
        if (!(spec.selection() instanceof EntitySelection)) {
            throw new UnsupportedOperationException(
                    "锁只支持实体查询: " + spec.entityType().getName());
        }
        return renderer.dialect().lock(lock, renderer.paths().aliases());
    }
}
