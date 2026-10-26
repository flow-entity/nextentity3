package io.github.nextentity.core.builder;

import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Selection;
import io.github.nextentity.core.spec.ValueSelection;
import java.util.ArrayList;
import java.util.List;

/// 后端返回的原始行 → 结果对象：实体行透传，投影查询一律经 `ValueSelection#rowMapper()` 映射。
///
/// **投影只有一条装配路径**：`select(...)` 的 1/2/3 列由 [ValueMappers] 的内置映射器造结果，
/// `project(...)` 用调用方给的映射器，两者在这里没有分支差异。列数只认 `ValueSelection#items()`，
/// 从不从行的运行时类型反推；行的长度与投影列数不符时在 [#tuple] 处直接拒绝，不猜形态。
/// 单列行「裸值 vs 长度 1 的 `Object[]`」在这里收成「1 个 cell」，映射器拿到的行恒与列数对齐。
///
/// 映射器取自 `selection` 本身而不是单独的参数：值查询一造出来就必有映射器，因此这里没有
/// 「有列集却没映射器」需要运行期拒绝的状态，装配只剩「包 `Tuple` 再 `map`」一条路。
///
/// 非目标：不校验投影（见 [ValueProjections]），也不做任何 SQL/方言决策。
///
/// 包内可见：唯一生产调用点是同包的 `QueryBuilder#fetch`，装配规则不外溢成公开面。
final class RowAssembler {

    private RowAssembler() {}

    /// 装配一批原始行。
    ///
    /// 投影形态用 `switch` 而非 `instanceof`：`Selection` 是 sealed，新增形态时这里编译报错，
    /// 逼出对装配的实际决定（透传还是映射），不会静默落进「都不是」的兜底。
    ///
    /// @param rows      执行器返回的原始行，非 `null`
    /// @param selection 本次查询的投影形态
    /// @return 装配结果，顺序与 `rows` 一致，元素类型按 `selection` 而定：
    ///         实体查询下就是 `rows` 本身（同一实例，元素未被改动），值查询下是新列表
    @SuppressWarnings("unchecked") // 实体查询下行的元素就是实体本身，装配不改变元素类型
    public static List<Object> assemble(List<?> rows, Selection selection) {
        return switch (selection) {
            case EntitySelection ignored -> (List<Object>) rows; // 零拷贝：本层没有改动元素，不需要再复制一份
            case ValueSelection values -> mapAll(rows, values);
        };
    }

    private static List<Object> mapAll(List<?> rows, ValueSelection values) {
        List<Object> mapped = new ArrayList<>(rows.size());
        for (Object row : rows) {
            mapped.add(values.rowMapper().map(tuple(row, values)));
        }
        return mapped;
    }

    /// 把一行装配成 `Tuple`，列数由投影项个数给出。
    ///
    /// **行的 cell 数必须与投影列数相符**，不符就是后端给了尺寸不对的行（少给或多给列），
    /// 当场拒绝并把两个数字写进消息。这是刻意的：旧实现按投影列数逐列取值，短行在 `get(i)`
    /// 上抛 `ArrayIndexOutOfBoundsException`——后端错行被暴露出来；任何「按行长度猜形态」的
    /// 兜底都会把它变成静默错值（3 列投影遇到 2 个 cell 时 `get(2)` 取到第二列的值）。
    ///
    /// 单列是唯一的整形分支：后端可能把单列渲染成长度 1 的 `Object[]`（JPA 的 `Object[]` 行形态），
    /// 也可能直接给裸值，两者都成「1 个 cell」的 [SingleTuple]——单列查询里整行就是那一列。
    /// 于是数组长度不是 1 同样是「cell 数与列数不等」，走下面同一条拒绝路径，不另作解释。
    ///
    /// 多列行不是 `Object[]` 时按「1 个 cell 的一行」计：这是旧实现在此处同样接受的形状
    /// （旧 `JpaExecutor#shapeRow` 把它包成长度 1 的数组交给元组），保持同一接收面，不做别的事。
    /// 于是下面的判等对一切形状都成立，多给、少给列都被拒绝。
    ///
    /// @param row    后端交回的一行
    /// @param values 本次投影（`items()` 给出列数）
    /// @return 交给 `values.rowMapper()` 的元组，列数恒等于 `values.items().size()`
    /// @throws IllegalStateException 行的 cell 数与投影列数不等
    private static Tuple tuple(Object row, ValueSelection values) {
        int columns = values.items().size();
        Object[] cells = cellsOf(row);
        if (cells.length != columns) {
            throw new IllegalStateException("行 cell 数与投影列数不等：行 " + cells.length + " 个 cell，投影 " + columns
                    + " 列。装配不自选形态（按行长度猜形态只会把后端错行变成静默错值），请检查该查询的行成形");
        }
        return columns == 1 ? new SingleTuple(cells[0]) : new TupleValue(cells);
    }

    /// 把后端交回的一行数成 cell 序列：`Object[]` 行原样，非数组行就是「1 个 cell 的一行」。
    ///
    /// 只做这一步数数，不做「像几列就造几列」的猜测：数出来的长度随后要与投影列数判等。
    ///
    /// @param row 后端交回的一行
    /// @return 该行的 cell 序列（数组行是原数组，不复制）
    private static Object[] cellsOf(Object row) {
        return row instanceof Object[] cells ? cells : new Object[] {row};
    }
}
