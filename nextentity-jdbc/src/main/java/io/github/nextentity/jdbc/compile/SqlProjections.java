package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.ComputedProjection;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.Selection;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import java.util.ArrayList;
import java.util.List;

/// SELECT 列表渲染：实体列集合 / 值投影 / 聚合，并登记结果列静态类型。
final class SqlProjections {

    private final SqlRenderer renderer;

    SqlProjections(SqlRenderer renderer) {
        this.renderer = renderer;
    }

    /// 渲染选择形态。
    ///
    /// @param selection 实体选择或值选择
    /// @return SELECT 列表片段（值选择含 `distinct` 前缀）
    String render(Selection selection) {
        if (selection instanceof EntitySelection) {
            return entityColumns();
        }
        ValueSelection values = (ValueSelection) selection;
        List<String> items = new ArrayList<>();
        for (Projection projection : values.items()) {
            items.add(projection(projection));
        }
        String joined = String.join(", ", items);
        return values.distinct() ? "distinct " + joined : joined;
    }

    /// 只渲染表达式，不登记 `columnTypes`（`count(distinct ...)` 复用）。
    ///
    /// @param projection 投影项
    /// @return SQL 表达式
    String expression(Projection projection) {
        return switch (projection) {
            case PathProjection path -> renderer.paths().resolve(path.path()).expression();
            case AggregateProjection aggregate -> aggregate(aggregate);
            case ComputedProjection computed -> new SqlOperands(renderer).expression(computed.operand());
        };
    }

    private String projection(Projection projection) {
        String expression = expression(projection);
        renderer.addColumnType(type(projection));
        return expression;
    }

    private Class<?> type(Projection projection) {
        return switch (projection) {
            case PathProjection path ->
                SqlPaths.columnType(renderer.paths().resolve(path.path()).attribute());
            case AggregateProjection aggregate -> aggregateType(aggregate);
            case ComputedProjection computed -> new SqlOperands(renderer).type(computed.operand());
        };
    }

    private String entityColumns() {
        JdbcEntityType<?> root = renderer.entityType();
        List<String> columns = new ArrayList<>();
        for (JdbcEntityAttribute attribute : root.attributes()) {
            if (attribute.isAssociation()) {
                continue;
            }
            columns.add(SqlPaths.ROOT_ALIAS + "." + attribute.column());
            renderer.addColumnType(SqlPaths.columnType(attribute));
        }
        return String.join(", ", columns);
    }

    private String aggregate(AggregateProjection aggregate) {
        String inner = renderer.paths().resolve(aggregate.path()).expression();
        return switch (aggregate.function()) {
            case MAX -> "max(" + inner + ")";
            case MIN -> "min(" + inner + ")";
            case AVG -> "avg(" + inner + ")";
            case SUM -> "sum(" + inner + ")";
            case COUNT -> "count(" + inner + ")";
            case COUNT_DISTINCT -> "count(distinct " + inner + ")";
        };
    }

    private Class<?> aggregateType(AggregateProjection aggregate) {
        return switch (aggregate.function()) {
            case COUNT, COUNT_DISTINCT -> Long.class;
            case AVG -> Double.class;
            case MAX, MIN, SUM ->
                SqlPaths.columnType(renderer.paths().resolve(aggregate.path()).attribute());
        };
    }
}
