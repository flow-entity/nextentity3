package io.github.nextentity.core.builder;

import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.core.spec.Selection;
import io.github.nextentity.core.spec.ValueSelection;
import java.util.ArrayList;
import java.util.List;

/// 后端返回的原始行 → 结果对象：实体行透传、元组装配、映射器映射都在这里。
///
/// 它依赖后端的行形态约定：`JpaExecutor.shapeRow` 对单列投影只在行是**长度 1** 的 `Object[]`
/// 时拆包，长度不是 1 的数组会原样作为该列的值。因此列数只能取自投影项个数，不能从行的运行时
/// 类型反推；这条约定跨模块、从本类代码看不出来，换后端或改 `shapeRow` 时必须同步这里。
///
/// 非目标：不校验投影（见 [ValueProjections]），也不做任何 SQL/方言决策。
final class RowAssembler {

    private RowAssembler() {}

    /// 装配一批原始行。
    ///
    /// @param rows      执行器返回的原始行，非 `null`
    /// @param selection 本次查询的投影形态
    /// @param mapper    映射器；`null` 表示按 `select(...)` 的形态装配
    /// @return 实体查询原样返回 `rows`；投影查询返回装配好的 `Tuple`；有映射器时返回 `mapper.map(...)` 的结果
    static List<Object> assemble(List<Object> rows, Selection selection, ProjectionMapper<?, ?> mapper) {
        if (!(selection instanceof ValueSelection values)) {
            return rows;
        }
        int columns = values.items().size();
        if (mapper != null) {
            List<Object> mapped = new ArrayList<>(rows.size());
            for (Object row : rows) {
                mapped.add(mapper.map(tuple(row, columns)));
            }
            return mapped;
        }
        if (columns == 1) {
            return rows;
        }
        boolean pair = columns == 2;
        List<Object> assembled = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object[] cells = (Object[]) row;
            assembled.add(pair ? new Tuple2Value<>(cells[0], cells[1]) : new TupleValue(cells));
        }
        return assembled;
    }

    /// 把一行装配成 `Tuple`，列数由投影项个数给出。
    ///
    /// 长度 1 的数组列值与单列行在行形态上无法区分，因此长度 1 的行一律按"单列"拆包；
    /// 映射器路径统一产生按位置取值的 [TupleValue]，单列包成长度为 1 的元组（`map` 只收 `Tuple`），
    /// 两列也不换成 [Tuple2Value]——映射器不需要具名访问器。
    private static Tuple tuple(Object row, int columns) {
        return new TupleValue(columns == 1 ? new Object[] {row} : (Object[]) row);
    }
}
