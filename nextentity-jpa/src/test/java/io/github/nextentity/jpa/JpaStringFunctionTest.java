package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.ShopOrder;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 字符串函数与 NOT LIKE 的渲染：标准 `substring(x, pos)` 形态（不下发 `2147483647` 这类巨数字面量），
/// `notContains` / `notStartsWith` / `notEndsWith` 折成 `NOT (LIKE ...)`。
class JpaStringFunctionTest extends JpaTestSupport {

    @Test
    @DisplayName("lower() 渲染成 lower(...)，过滤不区分大小写")
    void lowerRendersAndMatchesCaseInsensitively() {
        drainSql();
        List<ShopOrder> rows = inTx(entityManager -> orders(entityManager)
                .query()
                .where(customerName())
                .lower()
                .contains("ali")
                .orderBy(ShopOrder::getId)
                .asc()
                .list());

        assertThat(rows).extracting(ShopOrder::getId).containsExactly(100L);
        String sql = singleStatement();
        assertThat(sql).contains("lower(").contains("like");
    }

    @Test
    @DisplayName("upper() / trim() 渲染成标准函数")
    void upperAndTrimRenderStandardFunctions() {
        drainSql();
        List<ShopOrder> upper = inTx(entityManager -> orders(entityManager)
                .query()
                .where(customerName())
                .upper()
                .eq("ALICE")
                .list());
        assertThat(upper).extracting(ShopOrder::getId).containsExactly(100L);
        assertThat(singleStatement()).contains("upper(");

        drainSql();
        List<ShopOrder> trimmed = inTx(entityManager -> orders(entityManager)
                .query()
                .where(customerName())
                .trim()
                .eq("Alice")
                .list());
        assertThat(trimmed).extracting(ShopOrder::getId).containsExactly(100L);
        assertThat(singleStatement()).contains("trim(both from ");
    }

    @Test
    @DisplayName("length() 渲染成 length(...)，可以参与比较")
    void lengthRendersStandardFunction() {
        drainSql();
        List<ShopOrder> rows = inTx(entityManager -> orders(entityManager)
                .query()
                .where(customerName())
                .length()
                .gt(4)
                .orderBy(ShopOrder::getId)
                .asc()
                .list());

        assertThat(rows).extracting(ShopOrder::getId).containsExactly(100L);
        String sql = singleStatement();
        assertThat(sql).contains("character_length(").doesNotContain("2147483647");
    }

    @Test
    @DisplayName("substring(offset) 渲染成两参 substring(x, pos)，不补巨数长度")
    void substringWithoutLengthRendersTwoArguments() {
        drainSql();
        List<ShopOrder> rows = inTx(entityManager -> orders(entityManager)
                .query()
                .where(customerName())
                .substring(1)
                .eq("lice")
                .list());

        assertThat(rows).extracting(ShopOrder::getId).containsExactly(100L);
        String sql = singleStatement();
        assertThat(sql).contains("substring(").doesNotContain("2147483647");
        assertThat(argumentCount(sql, "substring"))
                .as("省略长度必须走标准两参形态 SUBSTRING(x, pos)")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("substring(offset, length) 渲染成三参 substring(x, pos, len)")
    void substringWithLengthRendersThreeArguments() {
        drainSql();
        List<ShopOrder> rows = inTx(entityManager -> orders(entityManager)
                .query()
                .where(customerName())
                .substring(1, 2)
                .eq("li")
                .list());

        assertThat(rows).extracting(ShopOrder::getId).containsExactly(100L);
        String sql = singleStatement();
        assertThat(sql).doesNotContain("2147483647");
        assertThat(argumentCount(sql, "substring")).isEqualTo(3);
    }

    @Test
    @DisplayName("函数可以嵌套：substring(...).length()")
    void functionsNest() {
        drainSql();
        List<ShopOrder> rows = inTx(entityManager -> orders(entityManager)
                .query()
                .where(customerName())
                .substring(1)
                .length()
                .gt(2)
                .list());

        assertThat(rows).extracting(ShopOrder::getId).containsExactly(100L);
        String sql = singleStatement();
        assertThat(sql).contains("substring(").contains("character_length(");
    }

    /// 负向谓词走 SQL 的 `not like`，因此服从三值逻辑：LEFT JOIN 过来的 NULL 行（101 号订单没有
    /// customer）在 `not like` 下是 UNKNOWN，不会出现；只有关联存在且不匹配的行才回来。
    @Test
    @DisplayName("notContains / notStartsWith / notEndsWith 折成 NOT (LIKE ...)，Hibernate 再归一成 not like")
    void negationRendersAsNotLike() {
        drainSql();
        List<ShopOrder> notContains = inTx(entityManager -> orders(entityManager)
                .query()
                .where(customerName())
                .notContains("zzz")
                .list());
        assertThat(notContains)
                .as("Alice 不含 zzz，NULL 关联行按三值逻辑被排除")
                .extracting(ShopOrder::getId)
                .containsExactly(100L);
        String notContainsSql = singleStatement();
        assertThat(notContainsSql).contains("not like").doesNotContain("not (");

        // 反向断言：pattern 命中（'Alice' 包含 'lic'）时该行不再返回，NULL 行同样不返回。
        drainSql();
        List<ShopOrder> matched = inTx(entityManager -> orders(entityManager)
                .query()
                .where(customerName())
                .notContains("lic")
                .list());
        assertThat(matched).as("'Alice' 命中 pattern，取反后为空").isEmpty();
        assertThat(singleStatement()).contains("not like");

        drainSql();
        List<ShopOrder> notStartsWith = inTx(entityManager -> orders(entityManager)
                .query()
                .where(customerName())
                .notStartsWith("zz")
                .list());
        assertThat(notStartsWith).extracting(ShopOrder::getId).containsExactly(100L);
        assertThat(singleStatement()).contains("not like");

        drainSql();
        List<ShopOrder> notEndsWith = inTx(entityManager -> orders(entityManager)
                .query()
                .where(customerName())
                .notEndsWith("zz")
                .list());
        assertThat(notEndsWith).extracting(ShopOrder::getId).containsExactly(100L);
        assertThat(singleStatement()).contains("not like");
    }

    private StringPath<ShopOrder> customerName() {
        return EntityPath.of(ShopOrder::getCustomer).get(Customer::getName);
    }

    private String singleStatement() {
        List<String> statements = normalized(drainSql());
        assertThat(statements).as("本条用例应当只发一条语句").hasSize(1);
        return statements.getFirst();
    }

    private static int argumentCount(String sql, String function) {
        Matcher matcher = Pattern.compile(function + "\\(([^)]*)\\)").matcher(sql);
        assertThat(matcher.find()).as("SQL 里应当出现 %s(...)：%s", function, sql).isTrue();
        return matcher.group(1).split(",", -1).length;
    }

    private EntityOperations<ShopOrder> orders(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(ShopOrder.class);
    }
}
