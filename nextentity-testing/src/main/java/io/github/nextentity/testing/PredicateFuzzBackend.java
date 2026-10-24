package io.github.nextentity.testing;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.Predicate;
import java.util.List;

/// 后端渲染器：概念树 → DSL 查询步骤。**只读概念树**，不看 [Memory]。
///
/// 合取链逐段挂到同一个构建器上（`where` 累加），标量节点先降成 `Predicate` 再用
/// `and`/`or`/`not` 组合；字符串匹配叶子只能走链式算子，因此它必须落在合取链的位置上。
///
/// @param <E> 实体类型
final class Backend<E extends Item> {

    private final ConsistencyTestBase<E> base;

    private final EntityQuery<E> query;

    Backend(ConsistencyTestBase<E> base) {
        this.base = base;
        this.query = base.query();
    }

    /// 把概念树降成 DSL 查询后交给真库执行，返回升序的 id 列表。
    List<Long> ids(Tree.And tree) {
        return render(tree).orderBy(base.idRef()).asc().list().stream()
                .map(Item::id)
                .toList();
    }

    BaseWhereStep<E, E> render(Tree.And tree) {
        BaseWhereStep<E, E> step = query;
        for (Tree part : tree.parts()) {
            step = attach(step, part);
        }
        return step;
    }

    private BaseWhereStep<E, E> attach(BaseWhereStep<E, E> step, Tree tree) {
        return switch (tree) {
            case Tree.Scalar scalar -> step.where(expression(scalar));
            case Tree.Contains contains ->
                step.where(textRef(contains.column())).contains(contains.text());
            case Tree.Like like -> step.where(textRef(like.column())).like(like.pattern());
        };
    }

    private Predicate<E> expression(Tree.Scalar scalar) {
        return switch (scalar) {
            case Tree.Eq node -> eq(node);
            case Tree.Range node -> range(node);
            case Tree.In node -> in(node);
            case Tree.IsNull node -> isNull(node);
            case Tree.Or node -> expression(node.left()).or(expression(node.right()));
            case Tree.Not node -> expression(node.child()).not();
        };
    }

    private Predicate<E> eq(Tree.Eq node) {
        return switch (node.column()) {
            case Column.Number number -> Path.of(numberRef(number)).eq(((Value.Number) node.value()).value());
            case Column.Text text -> Path.of(textRef(text)).eq(((Value.Text) node.value()).text());
            case Column.Status status -> Path.of(statusRef(status)).eq(((Value.Status) node.value()).status());
        };
    }

    private Predicate<E> range(Tree.Range node) {
        return switch (node.column()) {
            case SCORE -> Path.of(base.scoreRef()).between(node.from(), node.to());
            case SCORE2 -> Path.of(base.score2Ref()).between(node.from(), node.to());
        };
    }

    private Predicate<E> in(Tree.In node) {
        return switch (node.column()) {
            case Column.Number number ->
                Path.of(numberRef(number))
                        .in(node.values().stream()
                                .map(value -> ((Value.Number) value).value())
                                .toList());
            case Column.Text text ->
                Path.of(textRef(text))
                        .in(node.values().stream()
                                .map(value -> ((Value.Text) value).text())
                                .toList());
            case Column.Status status ->
                Path.of(statusRef(status))
                        .in(node.values().stream()
                                .map(value -> ((Value.Status) value).status())
                                .toList());
        };
    }

    private Predicate<E> isNull(Tree.IsNull node) {
        return switch (node.column()) {
            case Column.Number number -> Path.of(numberRef(number)).isNull();
            case Column.Text text -> Path.of(textRef(text)).isNull();
            case Column.Status status -> Path.of(statusRef(status)).isNull();
        };
    }

    private PathRef<E, Integer> numberRef(Column.Number column) {
        return switch (column) {
            case SCORE -> base.scoreRef();
            case SCORE2 -> base.score2Ref();
        };
    }

    private PathRef.StringRef<E> textRef(Column.Text column) {
        return switch (column) {
            case NOTE -> base.noteRef();
            case NAME -> base.nameRef();
        };
    }

    private PathRef<E, ItemStatus> statusRef(Column.Status status) {
        return switch (status) {
            case STATUS -> base.statusRef();
        };
    }
}
