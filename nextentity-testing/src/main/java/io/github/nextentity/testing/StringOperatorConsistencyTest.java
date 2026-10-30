package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 字符串函数与 NOT LIKE 的一致性：期望值全部由 {@link FixtureRow} 在内存里算（差分 oracle），
/// 不顺手写死「命中 id=1」这类常量，否则后端算错时用例照样通过。
///
/// 后端接线只需给出 {@link ConsistencySupport}：夹具、属性引用与查询入口都由它提供。
///
/// @param <T> 后端实体类型
public abstract class StringOperatorConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    private static final String UPPER_NAME = "BANANA";
    private static final String TRIMMED_NAME = "cherry";

    @Test
    @DisplayName("lower() 与内存 toLowerCase 一致")
    void lowerMatchesInMemory() {
        assertMatches(
                query().where(nameRef()).lower().eq(APPLE_NAME).list(),
                row -> row.name().toLowerCase(Locale.ROOT).equals(APPLE_NAME));
    }

    @Test
    @DisplayName("lower() + contains 与内存 contains 一致")
    void lowerContainsMatchesInMemory() {
        assertMatches(
                query().where(nameRef()).lower().contains("an").list(),
                row -> row.name().toLowerCase(Locale.ROOT).contains("an"));
    }

    @Test
    @DisplayName("upper() 与内存 toUpperCase 一致")
    void upperMatchesInMemory() {
        assertMatches(
                query().where(nameRef()).upper().eq(UPPER_NAME).list(),
                row -> UPPER_NAME.equals(row.name().toUpperCase(Locale.ROOT)));
    }

    @Test
    @DisplayName("trim() 与内存 trim 一致")
    void trimMatchesInMemory() {
        assertMatches(
                query().where(nameRef()).trim().eq(TRIMMED_NAME).list(),
                row -> row.name().trim().equals(TRIMMED_NAME));
    }

    @Test
    @DisplayName("length() 与内存 length 一致")
    void lengthMatchesInMemory() {
        assertMatches(
                query().where(nameRef()).length().gt(5).list(),
                row -> row.name().length() > 5);
    }

    @Test
    @DisplayName("substring(offset)：0 基、到结尾，与内存 substring 一致")
    void substringFromOffsetMatchesInMemory() {
        assertMatches(
                query().where(nameRef()).substring(6).eq("pie").list(),
                row -> row.name().length() > 6 && row.name().substring(6).equals("pie"));
    }

    @Test
    @DisplayName("substring(offset, length) 与内存 substring 一致")
    void substringWithLengthMatchesInMemory() {
        assertMatches(
                query().where(nameRef()).substring(0, 3).eq("app").list(),
                row -> row.name().substring(0, 3).equals("app"));
    }

    @Test
    @DisplayName("函数可以嵌套：substring(...).length()")
    void nestedFunctionsMatchInMemory() {
        assertMatches(
                query().where(nameRef()).substring(1).length().gt(4).list(),
                row -> row.name().substring(1).length() > 4);
    }

    @Test
    @DisplayName("notContains 与内存 !contains 一致")
    void notContainsMatchesInMemory() {
        assertMatches(query().where(nameRef()).notContains("e").list(), row -> !row.name()
                .contains("e"));
    }

    @Test
    @DisplayName("notStartsWith 与内存 !startsWith 一致")
    void notStartsWithMatchesInMemory() {
        assertMatches(query().where(nameRef()).notStartsWith(APPLE_NAME).list(), row -> !row.name()
                .startsWith(APPLE_NAME));
    }

    @Test
    @DisplayName("notEndsWith 与内存 !endsWith 一致")
    void notEndsWithMatchesInMemory() {
        assertMatches(query().where(nameRef()).notEndsWith("e").list(), row -> !row.name()
                .endsWith("e"));
    }

    private void assertMatches(List<T> actual, Predicate<FixtureRow> predicate) {
        List<Long> expected = fixtureRows().stream()
                .filter(predicate)
                .map(FixtureRow::id)
                .sorted()
                .toList();
        assertThat(actual.stream().map(Item::id).sorted().toList()).isEqualTo(expected);
    }
}
