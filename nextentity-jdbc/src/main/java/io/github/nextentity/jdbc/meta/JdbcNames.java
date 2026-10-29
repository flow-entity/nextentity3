package io.github.nextentity.jdbc.meta;

/// 标识符名的反引号归一化（§4.1 Phase F）：`` `order` `` → 裸名 `order` + quoted 标志。
///
/// 注解里的名字可能带反引号以标记「这是保留字 / 存量标识符，SQL 里必须引用」；
/// meta 一律按裸名做匹配与比较，把 quoted 标志交给渲染层决定是否过 `JdbcDialect#quote`。
///
/// 被标记引用的名字剥引号后不得再含引用定界符：Hibernate 不转义内层引号（JPA 侧同样不可用），
/// 若 JDBC 单侧做双写转义，同一注解在两个后端行为就分叉，故 §4.3 #28 在 meta 期一致拒绝。
final class JdbcNames {

    private static final char QUOTE = '`';
    private static final String DELIMITER_REJECTED = "标识符名不能含引用定界符（\" ` [ ]）: ";

    private JdbcNames() {}

    /// 去掉包裹的一对反引号；未包裹时只裁首尾空白。
    static String bare(String name) {
        String trimmed = name.trim();
        if (isQuoted(trimmed)) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /// 归一化并校验：被反引号标记的名字，剥引号后含 `"` / `` ` `` / `[` / `]` 任一 → §4.3 #28 拒绝。
    ///
    /// @param name 注解里声明的名字（可能带反引号标记）
    /// @param path 出错时定位用的「实体」或「实体.字段」路径，非 `null`
    static String bare(String name, String path) {
        String trimmed = name.trim();
        if (!isQuoted(trimmed)) {
            return trimmed;
        }
        String bare = bare(trimmed);
        rejectDelimiter(bare, path);
        return bare;
    }

    /// 是否被一对反引号包裹。
    static boolean isQuoted(String name) {
        String trimmed = name.trim();
        return trimmed.length() >= 2 && trimmed.charAt(0) == QUOTE && trimmed.charAt(trimmed.length() - 1) == QUOTE;
    }

    private static void rejectDelimiter(String name, String path) {
        if (containsDelimiter(name)) {
            throw new UnsupportedOperationException(DELIMITER_REJECTED + path + "." + name);
        }
    }

    private static boolean containsDelimiter(String name) {
        return name.indexOf('"') >= 0 || name.indexOf('`') >= 0 || name.indexOf('[') >= 0 || name.indexOf(']') >= 0;
    }
}
