package io.github.nextentity.api;

import java.util.List;

/// 线格式用例共用的夹具：一条订单 + 客户 + 组织，以及「按路径链逐级 apply」的读取工具。
///
/// 路径断言的证据是 [PathRef#apply(Object)] 真正取到的字段值，因此夹具只提供取值入口，**不重写
/// `equals`**：一旦给实体补上值相等，取错字段也可能因为两个字段值恰好相同而通过，断言就退化了。
final class WireFormatFixture {

    static final Integer AGE = 37;
    static final Integer SCORE = 97;
    static final Integer LEVEL = 4;
    static final Double SALARY = 1200.5;
    static final Double BONUS = 300.25;
    static final Double BASE_SALARY = 900.75;
    static final String STATUS = "PAID";
    static final String CUSTOMER_NAME = "Ada";
    static final String ORG_CODE = "ORG-7";

    private WireFormatFixture() {}

    static Order order() {
        return new Order(
                AGE, SCORE, LEVEL, SALARY, BONUS, BASE_SALARY, STATUS, new Customer(CUSTOMER_NAME, new Org(ORG_CODE)));
    }

    /// 逐级 apply 整条路径链：第 `i` 段的入参是第 `i-1` 段的返回值。
    ///
    /// @param refs 路径链，根→叶
    /// @return 叶子属性取到的值
    static Object read(List<PathRef<?, ?>> refs) {
        return read(refs, order());
    }

    /// 同上，但根实体由调用方给：要断言「取到的就是这条实体上的那个对象」时不能另造一个夹具实例。
    ///
    /// @param refs 路径链，根→叶
    /// @param root 根实体
    /// @return 叶子属性取到的值
    static Object read(List<PathRef<?, ?>> refs, Object root) {
        Object current = root;
        for (PathRef<?, ?> ref : refs) {
            current = apply(ref, current);
        }
        return current;
    }

    /// 把路径链上的一段应用到具体值上。
    ///
    /// 链元素声明成通配符（`PathRef<?, ?>`），这里用一段已擦除的视图调 `apply`，让逐级取值不必逐段强转。
    ///
    /// @param ref    链上的一段
    /// @param source 该段的入参（根段是实体，后续段是上一层取到的关联对象）
    /// @return 该段取到的值
    static Object apply(PathRef<?, ?> ref, Object source) {
        return ((PathRef<Object, Object>) ref).apply(source);
    }

    static final class Order {

        private final Integer age;
        private final Integer score;
        private final Integer level;
        private final Double salary;
        private final Double bonus;
        private final Double baseSalary;
        private final String status;
        private final Customer customer;

        Order(
                Integer age,
                Integer score,
                Integer level,
                Double salary,
                Double bonus,
                Double baseSalary,
                String status,
                Customer customer) {
            this.age = age;
            this.score = score;
            this.level = level;
            this.salary = salary;
            this.bonus = bonus;
            this.baseSalary = baseSalary;
            this.status = status;
            this.customer = customer;
        }

        public Integer getAge() {
            return age;
        }

        public Integer getScore() {
            return score;
        }

        public Integer getLevel() {
            return level;
        }

        public Double getSalary() {
            return salary;
        }

        public Double getBonus() {
            return bonus;
        }

        public Double getBaseSalary() {
            return baseSalary;
        }

        public String getStatus() {
            return status;
        }

        public Customer getCustomer() {
            return customer;
        }
    }

    static final class Customer {

        private final String name;
        private final Org org;

        Customer(String name, Org org) {
            this.name = name;
            this.org = org;
        }

        public String getName() {
            return name;
        }

        public Org getOrg() {
            return org;
        }
    }

    static final class Org {

        private final String code;

        Org(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
