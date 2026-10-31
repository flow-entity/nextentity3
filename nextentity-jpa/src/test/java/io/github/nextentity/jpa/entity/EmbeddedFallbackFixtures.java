package io.github.nextentity.jpa.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `select(Dto.class)` 回找的边界夹具。
///
/// 四类形状缺一不可：嵌入里的**关联**（回找只认标量叶子，关联不是可投影列）、**直接属性与嵌入
/// 叶子同名**（直接属性必须赢）、**同名叶子直接与嵌套各一份**（歧义文案里的候选必须是合法路径）、
/// **同一可嵌入类型经两条分支复用**（菱形，两个候选都要收集到，不能剪掉第二个）。
/// 另有非持久化 getter 与嵌入叶子同名，用来钉「条件路径不经回找」这条边界。
public final class EmbeddedFallbackFixtures {

    private EmbeddedFallbackFixtures() {}

    /// 非持久化 getter 与嵌入叶子同名（`city`）：条件里用它只能按实体属性解析，解析不到就该报错，
    /// 不能被回找静默改写成 `address.city`。
    @Entity
    @Table(name = "transient_getter_holder")
    public static class TransientGetterHolder {

        @Id
        private Long id;

        @Embedded
        private ShipmentAddress address;

        public TransientGetterHolder() {}

        public TransientGetterHolder(Long id, ShipmentAddress address) {
            this.id = id;
            this.address = address;
        }

        /// 非持久化 getter：字段访问下未被注解的方法不进元模型。
        ///
        /// @return 嵌入地址里的城市
        public String getCity() {
            return address == null ? null : address.getCity();
        }
    }

    /// 二级嵌入的叶子 `code`：列名交给隐式命名（`<路径>_inner_code`），这样同一 [EchoShell] 在实体上
    /// 出现两次也不会撞列名——菱形用例要的正是「同一类型两条分支」而不是列名冲突。
    @Embeddable
    public static class EchoInner {

        private String code;

        public EchoInner() {}

        public EchoInner(String code) {
            this.code = code;
        }
    }

    /// 直层叶子 `code` + 二级嵌入里的同名叶子：两个候选分别是 `contact.code` 与 `contact.inner.code`。
    @Embeddable
    public static class EchoContact {

        @Column(name = "echo_code")
        private String code;

        @Embedded
        private EchoInner inner;

        public EchoContact() {}

        public EchoContact(String code, EchoInner inner) {
            this.code = code;
            this.inner = inner;
        }
    }

    @Entity
    @Table(name = "echo_embedded_holder")
    public static class EchoEmbeddedHolder {

        @Id
        private Long id;

        @Embedded
        private EchoContact contact;

        public EchoEmbeddedHolder() {}

        public EchoEmbeddedHolder(Long id, EchoContact contact) {
            this.id = id;
            this.contact = contact;
        }
    }

    /// 只含二级嵌入：叶子 `code` 在 [EchoInner] 里，要在 [EchoShell] 的下一层才命中。
    @Embeddable
    public static class EchoShell {

        @Embedded
        private EchoInner inner;

        public EchoShell() {}

        public EchoShell(EchoInner inner) {
            this.inner = inner;
        }
    }

    /// 同一可嵌入类型 [EchoShell] 在实体上出现两次（菱形复用）：叶子只在更深的 [EchoInner] 里命中，
    /// 所以两个候选都必须收集到——漏掉第二个就会静默挑一列，正是歧义拒绝要防的情形。
    @Entity
    @Table(name = "echo_diamond_holder")
    public static class EchoDiamondHolder {

        @Id
        private Long id;

        @Embedded
        @AttributeOverride(name = "inner.code", column = @Column(name = "left_code"))
        private EchoShell left;

        @Embedded
        @AttributeOverride(name = "inner.code", column = @Column(name = "right_code"))
        private EchoShell right;

        public EchoDiamondHolder() {}

        public EchoDiamondHolder(Long id, EchoShell left, EchoShell right) {
            this.id = id;
            this.left = left;
            this.right = right;
        }
    }

    /// 实体自有 `city` 列 + 嵌入里也有 `city`：DTO 属性名命中直接属性时不许回找。
    @Entity
    @Table(name = "shadowed_embedded_holder")
    public static class ShadowedEmbeddedHolder {

        @Id
        private Long id;

        @Column(name = "city")
        private String city;

        @Embedded
        @AttributeOverride(name = "city", column = @Column(name = "address_city"))
        private ShipmentAddress address;

        public ShadowedEmbeddedHolder() {}

        public ShadowedEmbeddedHolder(Long id, String city, ShipmentAddress address) {
            this.id = id;
            this.city = city;
            this.address = address;
        }
    }
}
