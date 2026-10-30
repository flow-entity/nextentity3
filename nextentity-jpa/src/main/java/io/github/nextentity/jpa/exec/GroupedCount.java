package io.github.nextentity.jpa.exec;

import jakarta.persistence.criteria.CriteriaQuery;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/// 「组数统计」的唯一实现点：把已渲染好的分组查询包成 `select count(*) from (分组查询)`。
///
/// 标准 JPA Criteria 没有「从子查询派生」的 API（`from(Subquery)` 不存在），Hibernate 6+ 的
/// `org.hibernate.query.criteria.JpaCriteriaQuery#createCountQuery()` 是唯一入口；而本模块的
/// hibernate-core 是 `test` scope（主代码保持 provider 中立，见根 pom 的 dependencyManagement
/// 注释），所以按类名反射解析，解析只做一次（[{@link #HIBERNATE}] 静态常量）。
///
/// **失败一律 fail-fast**：provider 不是 Hibernate、方法缺席、调用抛异常都抛
/// `UnsupportedOperationException`，不静默降级成「客户端把分组键查出来再 size」——那会把
/// 「一条 SQL」悄悄换成 O(组数) 传输，用户看不出语义与性能的差别。
final class GroupedCount {

    private static final String HIBERNATE_CRITERIA_QUERY = "org.hibernate.query.criteria.JpaCriteriaQuery";
    private static final String CREATE_COUNT_QUERY = "createCountQuery";

    /// 生产单例：JVM 内只解析一次；provider 缺席时也只在构造时失败一次，不反复反射。
    private static final GroupedCount HIBERNATE = new GroupedCount(HIBERNATE_CRITERIA_QUERY, CREATE_COUNT_QUERY);

    private final String compoundName;
    private final Class<?> criteriaQueryType;
    private final Method createCountQuery;
    private final String resolutionFailure;

    /// 解析一个 provider 的派生表计数入口。
    ///
    /// 解析失败**不在这里抛**：失败原因留给调用点统一成「组数统计不支持」的文案，
    /// 这样 provider 缺席时用户拿到的是一条可行动的错误，而不是 `NoSuchMethodException`。
    ///
    /// 包私有 + 允许指定类名：让「方法缺席」这条拒绝分支能用假接口喂出
    /// `assertThatThrownBy` 用例（见 `GroupedCountTest`），而不是只靠注释声明。
    ///
    /// @param compoundName 候选类型全名
    /// @param methodName   候选方法名
    GroupedCount(String compoundName, String methodName) {
        this.compoundName = compoundName;
        Class<?> type = null;
        Method method = null;
        String failure = null;
        try {
            type = Class.forName(compoundName);
        } catch (ClassNotFoundException e) {
            failure = "缺少类型 " + compoundName;
        }
        if (type != null) {
            try {
                method = type.getMethod(methodName);
            } catch (NoSuchMethodException e) {
                failure = "缺少方法 " + compoundName + "#" + methodName;
            }
        }
        this.criteriaQueryType = type;
        this.createCountQuery = method;
        this.resolutionFailure = failure;
    }

    /// 生产入口：把分组查询转成「组数」计数查询。
    ///
    /// @param grouped 已渲染好的分组查询（选分组键 + group by + having）
    /// @return 外层 `count(*)` 查询，返回组数
    /// @throws UnsupportedOperationException 当前 provider 不支持派生表计数
    static CriteriaQuery<Long> groupCount(CriteriaQuery<?> grouped) {
        return HIBERNATE.countQuery(grouped);
    }

    @SuppressWarnings("unchecked")
    CriteriaQuery<Long> countQuery(CriteriaQuery<?> grouped) {
        String provider = grouped.getClass().getName();
        if (resolutionFailure != null) {
            throw unsupported(provider, resolutionFailure);
        }
        if (!criteriaQueryType.isInstance(grouped)) {
            throw unsupported(provider, "查询实现不是 " + compoundName);
        }
        try {
            return (CriteriaQuery<Long>) createCountQuery.invoke(grouped);
        } catch (InvocationTargetException e) {
            throw unsupported(provider, CREATE_COUNT_QUERY + " 抛异常: " + e.getCause());
        } catch (ReflectiveOperationException e) {
            throw unsupported(provider, "调用 " + CREATE_COUNT_QUERY + " 失败: " + e);
        }
    }

    private static UnsupportedOperationException unsupported(String provider, String why) {
        return new UnsupportedOperationException("当前 JPA provider 不支持「组数统计」（需要 Hibernate 6+）："
                + "可用 exists() 判断是否有组，或改用 JDBC 后端；已用 provider=" + provider + "（" + why + "）");
    }
}
