package io.github.nextentity.testing;

import io.github.nextentity.api.model.Tuple2;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// 关联嵌套路径差分的**内存侧 oracle**：只读夹具原始值，自己按 SQL 语义算，
/// 不经过 nextentity 的任何生产代码，也不回读后端结果。
///
/// 夹具被当作 `asset LEFT JOIN owner` 的**结果集**：`ownerName` 为 `null` 就是 join 不出 owner 的行。
/// 三值逻辑在这里统一落地：`= / <> / > / < / between / in / notIn` 遇 `null` 一律 `UNKNOWN`
/// （`WHERE` 不保留），`isNull` / `isNotNull` 判的就是 `null` 本身。所以「`ne(alpha)` 里出现 `null`
/// 关联行」等价于「把 `NULL` 当普通值」或「嵌套路径被渲染成内连接」，两种写错都在这里暴露。
///
/// 这份 oracle 与真库一侧**没有任何共享代码**：它不解析 SQL、不调 `CriteriaBuilder`、
/// 不读 `Asset` 实体的查询结果——只看 {@link AssetFixture} 的原始字段。两条路径独立，
/// 结论才构成正确性证据（手算常量只钉已知语义，不承担这一职责）。
final class AssociationJoinOracle {

    private AssociationJoinOracle() {}

    /// 谓词矩阵的期望命中集合：逐行套三值逻辑。
    ///
    /// @param rows    夹具（join 结果集形态）
    /// @param matches 单行的判据，参数是 join 出来的 owner 名称，可为 `null`
    /// @return 命中的主键集合，保持夹具顺序
    static Set<Long> matchingIds(List<AssetFixture> rows, Predicate<String> matches) {
        return rows.stream()
                .filter(row -> matches.test(row.ownerName()))
                .map(AssetFixture::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /// 全表主键集合：用来断言「某格不是全表」，避免恒真断言。
    ///
    /// @param rows 夹具
    /// @return 全部主键
    static Set<Long> allIds(List<AssetFixture> rows) {
        return rows.stream().map(AssetFixture::id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /// 夹具的主键序列（夹具声明序）：分页期望侧按主键排序后的全量序列。
    ///
    /// @param rows 夹具
    /// @return 全部主键，按夹具序
    static List<Long> fixtureIds(List<AssetFixture> rows) {
        return rows.stream().map(AssetFixture::id).toList();
    }

    /// 后端返回行的主键集合：只做 `Asset#id()` 投影，不参与判据。
    ///
    /// @param rows 后端返回的行
    /// @return 主键集合
    static Set<Long> ids(List<? extends Asset> rows) {
        return rows.stream().map(Asset::id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /// 内存侧的排序结果：`(id, ownerName)` 序列，`ownerName` 可为 `null`。
    ///
    /// `null` 关联排在哪一侧由传入的 {@link NullOrdering} 决定（默认 MySQL 家族：升序在前；
    /// PostgreSQL 方言为升序在后）。这是要钉的**方言口径**，不是实现细节：换后端时按方言覆盖，
    /// 而不是把断言放宽成「非空部分有序」——放宽之后 `null` 行排错位置就再没人管了。
    ///
    /// 投影两列而不是一列，是因为断言要同时看出「排的是哪一行」和「那行的 join 值是什么」。
    ///
    /// @param rows          夹具
    /// @param nullOrdering  升序时 `null` 关联的相对位置（后端方言）
    /// @param ownerAscending join 列是否升序
    /// @param idAscending    主键是否升序
    /// @return 有序的 `(id, ownerName)` 序列
    static List<Tuple2<Long, String>> orderedPairs(
            List<AssetFixture> rows, NullOrdering nullOrdering, boolean ownerAscending, boolean idAscending) {
        Comparator<String> byName = nullOrdering.ascending(Comparator.<String>naturalOrder());
        Comparator<AssetFixture> byOwner = Comparator.comparing(AssetFixture::ownerName, byName);
        Comparator<AssetFixture> byId = Comparator.comparingLong(AssetFixture::id);
        // 降序用 Comparator#reversed 而不是对 compare 结果取负：取负会丢掉 int 的 MIN_VALUE 边界，
        // 也会被 SpotBugs 判为 RV_NEGATING_RESULT_OF_COMPARETO。
        if (!ownerAscending) {
            byOwner = byOwner.reversed();
        }
        if (!idAscending) {
            byId = byId.reversed();
        }
        List<AssetFixture> sorted = new ArrayList<>(rows);
        sorted.sort(byOwner.thenComparing(byId));
        return sorted.stream()
                .map(row -> new Tuple2<Long, String>(row.id(), row.ownerName()))
                .toList();
    }

    /// join 列的投影序列（含 `null`）。
    ///
    /// @param rows 已排好序的夹具
    /// @return owner 名称序列
    static List<String> ownerNames(List<AssetFixture> rows) {
        return rows.stream().map(AssetFixture::ownerName).toList();
    }

    /// 按主键分组后每组 `count(owner.name)`：`null` 关联自成一组，且该组计数为 `0`
    /// （SQL 的 `COUNT(列)` 跳过 `null`）。
    ///
    /// @param rows 夹具
    /// @return 主键 → 计数
    static Map<Long, Long> countPerId(List<AssetFixture> rows) {
        Map<Long, List<String>> groups = new LinkedHashMap<>();
        for (AssetFixture row : rows) {
            groups.computeIfAbsent(row.id(), id -> new ArrayList<>()).add(row.ownerName());
        }
        Map<Long, Long> counts = new LinkedHashMap<>();
        groups.forEach((id, ownerNames) ->
                counts.put(id, ownerNames.stream().filter(Objects::nonNull).count()));
        return counts;
    }

    /// SQL 的 `COUNT(列)` / `COUNT(DISTINCT 列)` / `MIN` / `MAX` 都跳过 `null`。
    ///
    /// @param rows    夹具
    /// @param measure 聚合函数
    /// @param scope   取值域
    /// @return 聚合结果，`min`/`max` 在空域上是 `null`
    static Object aggregate(List<AssetFixture> rows, Measure measure, Scope scope) {
        List<String> joined = rows.stream()
                .map(AssetFixture::ownerName)
                .filter(ownerName -> scope == Scope.ALL_ROWS || ownerName == null)
                .toList();
        return switch (measure) {
            case COUNT -> (long) joined.stream().filter(Objects::nonNull).count();
            case COUNT_DISTINCT ->
                (long) joined.stream().filter(Objects::nonNull).distinct().count();
            case MIN ->
                joined.stream()
                        .filter(Objects::nonNull)
                        .min(Comparator.naturalOrder())
                        .orElse(null);
            case MAX ->
                joined.stream()
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null);
        };
    }

    /// 内存侧的最小 `LIKE` 实现：`%` 匹配任意长度、`_` 匹配单个字符，其余字符按字面量比。
    ///
    /// 只实现这两个通配符是刻意的——它们是 `like(...)` 契约里唯一被承认的写法（调用方自带通配符）。
    /// 转义符 `\` 不在本矩阵内，所以这里不为它写分支；真库若把 `_` 当字面量，对应格当场变红。
    ///
    /// @param value   被比较的值
    /// @param pattern 带通配符的模式
    /// @return 是否匹配
    static boolean like(String value, String pattern) {
        String regex = pattern.chars()
                .mapToObj(code -> switch ((char) code) {
                    case '%' -> ".*";
                    case '_' -> ".";
                    default -> Pattern.quote(String.valueOf((char) code));
                })
                .collect(Collectors.joining());
        return value.matches(regex);
    }

    /// 聚合函数。
    enum Measure {
        COUNT("count(owner.name)"),
        COUNT_DISTINCT("countDistinct(owner.name)"),
        MIN("min(owner.name)"),
        MAX("max(owner.name)");

        private final String label;

        Measure(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /// 聚合的取值域：全表 / 只取 `null` 关联行。
    ///
    /// 第二个域是关键：`min`/`max` 在全表上都被非空值占满，看不出「`null` 有没有被当成最小值」；
    /// 在「只有 `null` 关联行」的域上，`count` 必须是 `0`、`min`/`max` 必须是 `null`（不是 `0`/`""`）。
    enum Scope {
        ALL_ROWS("all rows"),
        NULL_JOIN_ROW("only null join rows");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }
}
