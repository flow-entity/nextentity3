package io.github.nextentity.jpa.entity;

import jakarta.persistence.AssociationOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 写侧「生效列解析」的补强夹具：`JpaMeta.embeddedColumn` / `embeddedJoinColumn` 的**回落**与
/// **递归**分支需要三类形状才走得到——叶子完全没有列注解（解析返回 null，按可写处理）、
/// 关联没有 `@JoinColumn`（外键列走默认命名）、二级嵌入里放只读关联（必须沿点号下钻才看到
/// `updatable=false`）。缺了这些形状，解析里「覆盖没命中 → 看叶子自己的注解」和「点号路径
/// 递归」两段就没有任何用例，写侧裁剪是否按生效列判可写也就无从验证。
public final class EmbeddedResolutionFixtures {

    private EmbeddedResolutionFixtures() {}

    /// 不带任何列注解的可嵌入类型：生效列只可能来自实体侧覆盖，没有覆盖时解析返回 null。
    @Embeddable
    public static class PlainContact {

        private String city;

        private String zip;

        public PlainContact() {}

        public PlainContact(String city, String zip) {
            this.city = city;
            this.zip = zip;
        }
    }

    @Entity
    @Table(name = "plain_embedded_flags")
    public static class PlainEmbeddedFlags {

        @Id
        private Long id;

        @Embedded
        private PlainContact contact;

        public PlainEmbeddedFlags() {}

        public PlainEmbeddedFlags(Long id, PlainContact contact) {
            this.id = id;
            this.contact = contact;
        }
    }

    /// 关联不声明 `@JoinColumn`：外键列由 JPA 默认命名推出，解析时拿不到 `@JoinColumn`。
    @Embeddable
    public static class UnannotatedOwnerRef {

        @ManyToOne(fetch = FetchType.LAZY)
        private Organization owner;

        public UnannotatedOwnerRef() {}

        public UnannotatedOwnerRef(Organization owner) {
            this.owner = owner;
        }
    }

    @Entity
    @Table(name = "unannotated_embedded_association")
    public static class UnannotatedEmbeddedAssociation {

        @Id
        private Long id;

        @Embedded
        private UnannotatedOwnerRef ref;

        public UnannotatedEmbeddedAssociation() {}

        public UnannotatedEmbeddedAssociation(Long id, UnannotatedOwnerRef ref) {
            this.id = id;
            this.ref = ref;
        }
    }

    /// 二级嵌入里放只读关联：解析必须沿点号递归到 `ownerRef.owner` 才看得到 `updatable=false`。
    @Embeddable
    public static class DeepOwnerRef {

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "deep_owner_id", updatable = false)
        private Organization owner;

        public DeepOwnerRef() {}

        public DeepOwnerRef(Organization owner) {
            this.owner = owner;
        }
    }

    @Embeddable
    public static class DeepContact {

        @Column(name = "deep_code")
        private String code;

        @Embedded
        private DeepOwnerRef ownerRef;

        public DeepContact() {}

        public DeepContact(String code, DeepOwnerRef ownerRef) {
            this.code = code;
            this.ownerRef = ownerRef;
        }
    }

    @Entity
    @Table(name = "deep_embedded_association_flags")
    public static class DeepEmbeddedAssociationFlags {

        @Id
        private Long id;

        @Embedded
        private DeepContact contact;

        public DeepEmbeddedAssociationFlags() {}

        public DeepEmbeddedAssociationFlags(Long id, DeepContact contact) {
            this.id = id;
            this.contact = contact;
        }
    }

    /// 唯一可写列藏在二级嵌入里：`setValues` / `setEmbedded` 的 `assigned` 聚合必须把内层结果
    /// 带出来，否则「外层全只读、内层有可写列」会被误判成没有可写列。
    @Embeddable
    public static class NestedInnerCode {

        @Column(name = "inner_code")
        private String code;

        public NestedInnerCode() {}

        public NestedInnerCode(String code) {
            this.code = code;
        }
    }

    @Embeddable
    public static class NestedOuterReadOnly {

        @Column(name = "outer_locked", updatable = false)
        private String locked;

        @Embedded
        private NestedInnerCode inner;

        public NestedOuterReadOnly() {}

        public NestedOuterReadOnly(String locked, NestedInnerCode inner) {
            this.locked = locked;
            this.inner = inner;
        }
    }

    @Entity
    @Table(name = "nested_only_writable_inside")
    public static class NestedOnlyWritableInside {

        @Id
        private Long id;

        @Embedded
        private NestedOuterReadOnly outer;

        public NestedOnlyWritableInside() {}

        public NestedOnlyWritableInside(Long id, NestedOuterReadOnly outer) {
            this.id = id;
            this.outer = outer;
        }
    }

    /// 覆盖名字与要写的关联不匹配：生效列匹配必须按名字精确比对，不能「见到覆盖就用」。
    @Entity
    @Table(name = "override_name_mismatch_flags")
    public static class OverrideNameMismatchFlags {

        @Id
        private Long id;

        @Embedded
        @AssociationOverride(
                name = "lockedCountry",
                joinColumns = @JoinColumn(name = "mismatch_locked_id", updatable = false))
        private ContactInfo contact;

        public OverrideNameMismatchFlags() {}

        public OverrideNameMismatchFlags(Long id, ContactInfo contact) {
            this.id = id;
            this.contact = contact;
        }
    }

    /// 嵌入叶子全部只读：`setEmbedded` 一条都没写时必须回报「没写」，让调用方显式拒绝空 SET。
    @Embeddable
    public static class AllLockedEmbeddable {

        @Column(name = "all_locked", updatable = false)
        private String locked;

        public AllLockedEmbeddable() {}

        public AllLockedEmbeddable(String locked) {
            this.locked = locked;
        }
    }

    @Entity
    @Table(name = "all_embedded_read_only")
    public static class AllEmbeddedReadOnly {

        @Id
        private Long id;

        @Embedded
        private AllLockedEmbeddable locked;

        public AllEmbeddedReadOnly() {}

        public AllEmbeddedReadOnly(Long id, AllLockedEmbeddable locked) {
            this.id = id;
            this.locked = locked;
        }
    }
}
