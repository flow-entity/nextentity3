package io.github.nextentity.jdbc.compile;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.Window;
import java.util.ArrayList;
import java.util.List;

/// 除 SELECT 列表以外的子句渲染：`where / group by / having / order by / limit offset / for update`。
///
/// 每个方法返回「带前导空格的子句」或空串，由 `SqlRenderer` 顺序拼接。
final class SqlClauses {

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
        if (window == null) {
            return "";
        }
        Integer offset = window.offset();
        Integer limit = window.limit();
        boolean hasOffset = offset != null && offset > 0;
        boolean hasLimit = limit != null && limit > 0;
        if (!hasLimit && !hasOffset) {
            return "";
        }
        // limit 缺省但给了 offset：MySQL 要求 LIMIT 在前，用哨兵 Long.MAX_VALUE（设计 §3.7）
        // 注意不能写成三元：`hasLimit ? limit : Long.MAX_VALUE` 会把 limit 提升成 long，绑定值类型就变了。
        if (hasLimit) {
            renderer.addParameter(limit);
        } else {
            renderer.addParameter(Long.MAX_VALUE);
        }
        if (!hasOffset) {
            return " limit ?";
        }
        renderer.addParameter(offset);
        return " limit ? offset ?";
    }

    String lock(QuerySpec spec) {
        LockMode lock = spec.lock();
        if (lock == null) {
            return "";
        }
        if (lock != LockMode.PESSIMISTIC_WRITE) {
            throw new UnsupportedOperationException("JDBC 后端只支持 PESSIMISTIC_WRITE: " + lock);
        }
        if (!(spec.selection() instanceof EntitySelection)) {
            throw new UnsupportedOperationException(
                    "锁只支持实体查询: " + spec.entityType().getName());
        }
        return " for update";
    }
}
