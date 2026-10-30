package io.github.nextentity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 字符串算子族里**默认方法**的语义：前缀/后缀/否定都拼成 `LIKE` / `NOT LIKE` 模式，
/// `*IfNotNull` / `*IfNotEmpty` 在值为空时短路（转到 `likeIfNotNull` / `notLikeIfNotNull`）。
///
/// 这里只钉「拼出的模式串」与「短路走哪条路」——这是 api 层能给出的全部承诺；
/// 模式在 SQL 里的效果由后端落库用例负责（api 不持有方言）。
/// 用记录调用的假实现而不是 mock 框架：断言的是**调用序列**，即 `startsWith("ann")`
/// 必须恰好等价于 `like("ann%")`，多拼一个 `%` 或少拼都算错。
///
/// 每条断言都同时钉住返回值就是假实现自身：短路与非短路两条路都必须把"下一步"交回去，
/// 返回 `null` 会让调用方链式写法的下一环空指针——这跟拼错模式串是两类错误，要分开钉。
@DisplayName("StringOperator 默认方法：模式拼接与空值短路")
class StringOperatorDefaultsTest {

    private final RecordingOperator operator = new RecordingOperator();

    @Test
    @DisplayName("startsWith / endsWith：只有一端带通配符")
    void prefixAndSuffixWrapOneWildcard() {
        assertThat(operator.startsWith("ann")).isSameAs(operator);
        assertThat(operator.endsWith("son")).isSameAs(operator);

        assertThat(operator.calls).containsExactly("like:ann%", "like:%son");
    }

    @Test
    @DisplayName("notStartsWith / notEndsWith / notContains：NOT LIKE 加对应通配符")
    void negativeFamilyWrapsNotLike() {
        assertThat(operator.notStartsWith("ann")).isSameAs(operator);
        assertThat(operator.notEndsWith("son")).isSameAs(operator);
        assertThat(operator.notContains("nn")).isSameAs(operator);

        assertThat(operator.calls).containsExactly("notLike:ann%", "notLike:%son", "notLike:%nn%");
    }

    @Test
    @DisplayName("*IfNotNull(null)：短路走 likeIfNotNull(null) / notLikeIfNotNull(null)，不拼模式串")
    void ifNotNullShortCircuitsNull() {
        assertThat(operator.startsWithIfNotNull(null)).isSameAs(operator);
        assertThat(operator.endsWithIfNotNull(null)).isSameAs(operator);
        assertThat(operator.notStartsWithIfNotNull(null)).isSameAs(operator);
        assertThat(operator.notEndsWithIfNotNull(null)).isSameAs(operator);
        assertThat(operator.notContainsIfNotNull(null)).isSameAs(operator);

        assertThat(operator.calls)
                .containsExactly(
                        "likeIfNotNull:null",
                        "likeIfNotNull:null",
                        "notLikeIfNotNull:null",
                        "notLikeIfNotNull:null",
                        "notLikeIfNotNull:null");
    }

    @Test
    @DisplayName("*IfNotNull(非 null)：照常拼模式串")
    void ifNotNullKeepsValue() {
        assertThat(operator.startsWithIfNotNull("ann")).isSameAs(operator);
        assertThat(operator.endsWithIfNotNull("son")).isSameAs(operator);
        assertThat(operator.notStartsWithIfNotNull("ann")).isSameAs(operator);
        assertThat(operator.notEndsWithIfNotNull("son")).isSameAs(operator);
        assertThat(operator.notContainsIfNotNull("nn")).isSameAs(operator);

        assertThat(operator.calls)
                .containsExactly("like:ann%", "like:%son", "notLike:ann%", "notLike:%son", "notLike:%nn%");
    }

    @Test
    @DisplayName("*IfNotEmpty(null)：空值短路，与 *IfNotNull 同一条路")
    void ifNotEmptyShortCircuitsNull() {
        assertThat(operator.startsWithIfNotEmpty(null)).isSameAs(operator);
        assertThat(operator.endsWithIfNotEmpty(null)).isSameAs(operator);
        assertThat(operator.notLikeIfNotEmpty(null)).isSameAs(operator);
        assertThat(operator.notStartsWithIfNotEmpty(null)).isSameAs(operator);
        assertThat(operator.notEndsWithIfNotEmpty(null)).isSameAs(operator);
        assertThat(operator.notContainsIfNotEmpty(null)).isSameAs(operator);

        assertThat(operator.calls)
                .containsExactly(
                        "likeIfNotNull:null",
                        "likeIfNotNull:null",
                        "notLikeIfNotNull:null",
                        "notLikeIfNotNull:null",
                        "notLikeIfNotNull:null",
                        "notLikeIfNotNull:null");
    }

    @Test
    @DisplayName("*IfNotEmpty(\"\")：空串也短路，不拼出 LIKE '%'")
    void ifNotEmptyShortCircuitsEmptyString() {
        assertThat(operator.startsWithIfNotEmpty("")).isSameAs(operator);
        assertThat(operator.endsWithIfNotEmpty("")).isSameAs(operator);
        assertThat(operator.notLikeIfNotEmpty("")).isSameAs(operator);
        assertThat(operator.notStartsWithIfNotEmpty("")).isSameAs(operator);
        assertThat(operator.notEndsWithIfNotEmpty("")).isSameAs(operator);
        assertThat(operator.notContainsIfNotEmpty("")).isSameAs(operator);

        assertThat(operator.calls)
                .containsExactly(
                        "likeIfNotNull:null",
                        "likeIfNotNull:null",
                        "notLikeIfNotNull:null",
                        "notLikeIfNotNull:null",
                        "notLikeIfNotNull:null",
                        "notLikeIfNotNull:null");
    }

    @Test
    @DisplayName("*IfNotEmpty(非空串)：照常拼模式串")
    void ifNotEmptyKeepsValue() {
        assertThat(operator.startsWithIfNotEmpty("ann")).isSameAs(operator);
        assertThat(operator.endsWithIfNotEmpty("son")).isSameAs(operator);
        assertThat(operator.notLikeIfNotEmpty("a%")).isSameAs(operator);
        assertThat(operator.notStartsWithIfNotEmpty("ann")).isSameAs(operator);
        assertThat(operator.notEndsWithIfNotEmpty("son")).isSameAs(operator);
        assertThat(operator.notContainsIfNotEmpty("nn")).isSameAs(operator);

        assertThat(operator.calls)
                .containsExactly(
                        "like:ann%",
                        "like:%son", "notLikeIfNotNull:a%", "notLike:ann%", "notLike:%son", "notLike:%nn%");
    }

    @Test
    @DisplayName("substring：省略长度与带长度是两个重载，各自转发（省略长度不下发上界数字）")
    void substringOverloadsAreDistinct() {
        assertThat(operator.substring(3)).isSameAs(operator);
        assertThat(operator.substring(3, 5)).isSameAs(operator);

        assertThat(operator.calls).containsExactly("substring:3", "substring:3,5");
    }

    @Test
    @DisplayName("lower / upper / trim / length：函数调用各记一次")
    void functionsAreDistinct() {
        assertThat(operator.lower()).isSameAs(operator);
        assertThat(operator.upper()).isSameAs(operator);
        assertThat(operator.trim()).isSameAs(operator);
        operator.length();

        assertThat(operator.calls).containsExactly("lower", "upper", "trim", "length");
    }

    // ---------------------------------------------------------------- fixture

    /// 记录每次算子调用的假实现：返回值都是自己，这样默认方法里的链式转发才写得通。
    private static final class RecordingOperator implements StringOperator<Object, RecordingOperator> {

        private final List<String> calls = new ArrayList<>();

        private RecordingOperator record(String call) {
            calls.add(call);
            return this;
        }

        @Override
        public RecordingOperator eq(String value) {
            return record("eq:" + value);
        }

        @Override
        public RecordingOperator eqIfNotNull(String value) {
            return record("eqIfNotNull:" + value);
        }

        @Override
        public RecordingOperator ne(String value) {
            return record("ne:" + value);
        }

        @Override
        public RecordingOperator isNull() {
            return record("isNull");
        }

        @Override
        public RecordingOperator isNotNull() {
            return record("isNotNull");
        }

        @Override
        public RecordingOperator in(Collection<? extends String> values) {
            return record("in:" + values);
        }

        @Override
        public RecordingOperator in(String... values) {
            return record("in:" + List.of(values));
        }

        @Override
        public RecordingOperator notIn(Collection<? extends String> values) {
            return record("notIn:" + values);
        }

        @Override
        public RecordingOperator notIn(String... values) {
            return record("notIn:" + List.of(values));
        }

        @Override
        public RecordingOperator inIfNotNull(Collection<? extends String> values) {
            return record("inIfNotNull:" + values);
        }

        @Override
        public RecordingOperator contains(String value) {
            return record("contains:" + value);
        }

        @Override
        public RecordingOperator containsIfNotEmpty(String value) {
            return record("containsIfNotEmpty:" + value);
        }

        @Override
        public RecordingOperator containsIfNotNull(String value) {
            return record("containsIfNotNull:" + value);
        }

        @Override
        public RecordingOperator like(String pattern) {
            return record("like:" + pattern);
        }

        @Override
        public RecordingOperator likeIfNotNull(String pattern) {
            return record("likeIfNotNull:" + pattern);
        }

        @Override
        public RecordingOperator eqIfNotEmpty(String value) {
            return record("eqIfNotEmpty:" + value);
        }

        @Override
        public RecordingOperator notLike(String pattern) {
            return record("notLike:" + pattern);
        }

        @Override
        public RecordingOperator notLikeIfNotNull(String pattern) {
            return record("notLikeIfNotNull:" + pattern);
        }

        @Override
        public StringOperator<Object, RecordingOperator> lower() {
            record("lower");
            return this;
        }

        @Override
        public StringOperator<Object, RecordingOperator> upper() {
            record("upper");
            return this;
        }

        @Override
        public StringOperator<Object, RecordingOperator> trim() {
            record("trim");
            return this;
        }

        @Override
        public StringOperator<Object, RecordingOperator> substring(int offset) {
            record("substring:" + offset);
            return this;
        }

        @Override
        public StringOperator<Object, RecordingOperator> substring(int offset, int length) {
            record("substring:" + offset + "," + length);
            return this;
        }

        @Override
        public NumberOperator<Object, RecordingOperator> length() {
            record("length");
            return new RecordingNumberOperator(this);
        }
    }

    /// `length()` 的返回类型只需要存在，方法体全转回字符串算子（本用例不断言数字算子的语义）。
    private static final class RecordingNumberOperator implements NumberOperator<Object, RecordingOperator> {

        private final RecordingOperator operator;

        private RecordingNumberOperator(RecordingOperator operator) {
            this.operator = operator;
        }

        @Override
        public RecordingOperator eq(Integer value) {
            return operator.record("number.eq:" + value);
        }

        @Override
        public RecordingOperator eqIfNotNull(Integer value) {
            return operator.record("number.eqIfNotNull:" + value);
        }

        @Override
        public RecordingOperator ne(Integer value) {
            return operator.record("number.ne:" + value);
        }

        @Override
        public RecordingOperator ge(Integer value) {
            return operator.record("number.ge:" + value);
        }

        @Override
        public RecordingOperator gt(Integer value) {
            return operator.record("number.gt:" + value);
        }

        @Override
        public RecordingOperator le(Integer value) {
            return operator.record("number.le:" + value);
        }

        @Override
        public RecordingOperator lt(Integer value) {
            return operator.record("number.lt:" + value);
        }

        @Override
        public RecordingOperator geIfNotNull(Integer value) {
            return operator.record("number.geIfNotNull:" + value);
        }

        @Override
        public RecordingOperator gtIfNotNull(Integer value) {
            return operator.record("number.gtIfNotNull:" + value);
        }

        @Override
        public RecordingOperator leIfNotNull(Integer value) {
            return operator.record("number.leIfNotNull:" + value);
        }

        @Override
        public RecordingOperator ltIfNotNull(Integer value) {
            return operator.record("number.ltIfNotNull:" + value);
        }

        @Override
        public RecordingOperator between(Integer from, Integer to) {
            return operator.record("number.between:" + from + "," + to);
        }

        @Override
        public RecordingOperator between(Expression<Object, Integer> from, Expression<Object, Integer> to) {
            return operator.record("number.betweenExpressions");
        }

        @Override
        public RecordingOperator notBetween(Integer from, Integer to) {
            return operator.record("number.notBetween:" + from + "," + to);
        }
    }
}
