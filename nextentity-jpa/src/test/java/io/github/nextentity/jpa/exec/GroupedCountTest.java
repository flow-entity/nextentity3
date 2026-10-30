package io.github.nextentity.jpa.exec;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.CriteriaQuery;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 「组数统计」的拒绝分支：provider 不是 Hibernate、入口方法缺席、查询实现不匹配、调用抛异常，
/// 都必须 fail-fast 抛 `UnsupportedOperationException`，不能静默降级。
class GroupedCountTest {

    @Test
    @DisplayName("provider 类型不存在：报「缺少类型」而不是 NoClassDefFoundError")
    void missingTypeIsReported() {
        GroupedCount counter = new GroupedCount("no.such.provider.MissingCriteriaQuery", "createCountQuery");

        assertThatThrownBy(() -> counter.countQuery(criteriaQuery()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("不支持「组数统计」")
                .hasMessageContaining("缺少类型 no.such.provider.MissingCriteriaQuery");
    }

    @Test
    @DisplayName("类型存在但没有派生表计数入口：报「缺少方法」")
    void missingMethodIsReported() {
        GroupedCount counter = new GroupedCount(CriteriaQuery.class.getName(), "createCountQuery");

        assertThatThrownBy(() -> counter.countQuery(criteriaQuery()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("缺少方法 " + CriteriaQuery.class.getName() + "#createCountQuery");
    }

    @Test
    @DisplayName("查询实现不是候选类型：报「查询实现不是」")
    void providerMismatchIsReported() {
        // String#length() 真实存在，但 CriteriaQuery 不满足 isInstance(String)
        GroupedCount counter = new GroupedCount(String.class.getName(), "length");
        CriteriaQuery<?> query = criteriaQuery();

        assertThatThrownBy(() -> counter.countQuery(query))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("查询实现不是 " + String.class.getName())
                // 报错里必须带真实实现类名，否则用户拿不到可行动的信息
                .hasMessageContaining("已用 provider=" + query.getClass().getName());
    }

    @Test
    @DisplayName("入口方法自己抛异常：包成 UOE 并带上原因")
    void failingCreateCountQueryIsWrapped() {
        GroupedCount counter = new GroupedCount(ExplodingCriteriaQuery.class.getName(), "createCountQuery");
        CriteriaQuery<?> exploding = (CriteriaQuery<?>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ExplodingCriteriaQuery.class}, (proxy, method, args) -> {
                    if ("createCountQuery".equals(method.getName())) {
                        throw new IllegalStateException("boom");
                    }
                    return null;
                });

        assertThatThrownBy(() -> counter.countQuery(exploding))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("createCountQuery 抛异常")
                .hasMessageContaining("boom");
    }

    /// 只用来喂「入口方法抛异常」分支：真实 provider 的入口不会抛。
    public interface ExplodingCriteriaQuery extends CriteriaQuery<Object> {

        @SuppressWarnings("unused")
        default CriteriaQuery<Long> createCountQuery() {
            throw new IllegalStateException("boom");
        }
    }

    private static CriteriaQuery<?> criteriaQuery() {
        return (CriteriaQuery<?>) Proxy.newProxyInstance(
                GroupedCountTest.class.getClassLoader(),
                new Class<?>[] {CriteriaQuery.class},
                (proxy, method, args) -> null);
    }
}
