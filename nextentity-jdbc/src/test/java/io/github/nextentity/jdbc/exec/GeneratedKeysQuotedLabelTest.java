package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/// Q3（引用标识符 / 生成键）：PG 的 `RETURN_GENERATED_KEYS` 返回整行 keys，label 是**裸列名**
/// （quoted 注解 `` @Column(name = "`id`") `` 在 meta 层已被剥成裸名 `id`），因此多列 keys 必须按裸名定位，
/// 不能把引号后的名字（`"id"` / `` `id` ``）拿去做 label 比较。
///
/// 这里用代理 ResultSet 模拟 PG 形态（两列：name 在前、ID 在后），并配一个 quoted id 注解的实体，
/// 把「meta 剥引号 → exec 按裸名匹配 label」这条跨层契约钉住。
class GeneratedKeysQuotedLabelTest {

    @Test
    void quotedIdColumnIsNormalizedToBareNameForGeneratedKeyLabelMatching() {
        JdbcEntityType<QuotedGenerated> type = new JdbcMeta().entityType(QuotedGenerated.class);

        assertThat(type.id().column()).as("quoted 注解剥引号后是裸名").isEqualTo("id");
        assertThat(type.id().isColumnQuoted()).as("quoted 标志单独保留，供渲染层引用").isTrue();
    }

    @Test
    void locatesBareIdLabelInWholeRowGeneratedKeys() throws Exception {
        ResultSet keys = resultSet(new String[] {"name", "ID"}, 17L);

        assertThat(GeneratedKeys.read(keys, "id", Long.class, "insert into \"gen_order\" (\"name\") values (?)"))
                .as("PG 整行 keys 的 label 是裸列名 id（大小写不敏感）")
                .isEqualTo(17L);
    }

    /// 模拟驱动返回「整行 keys」：列名/标签按给定数组，只有 id 列可转 BigDecimal，
    /// 第一列调用 `getBigDecimal` 抛 SQLException，确保实现真的按 label 定位而不是盲读第一列。
    private static ResultSet resultSet(String[] labels, long idValue) {
        ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> labels.length;
            case "getColumnLabel", "getColumnName" -> labels[(Integer) args[0] - 1];
            default -> defaultValue(method);
        });
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("getMetaData")) {
                return metadata;
            }
            if (method.getName().equals("getBigDecimal")) {
                int index = (Integer) args[0];
                if (labels[index - 1].equalsIgnoreCase("id")) {
                    return BigDecimal.valueOf(idValue);
                }
                throw new SQLException("column " + index + " is not numeric");
            }
            return defaultValue(method);
        };
        return proxy(ResultSet.class, handler);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }

    @Entity
    @Table(name = "`gen_order`")
    static class QuotedGenerated {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "`id`")
        private Long id;

        @Column(name = "`name`")
        private String name;

        protected QuotedGenerated() {}
    }
}
