package io.github.nextentity.jpa.entity;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Access(PROPERTY)` 实体：注解全写在 getter 上，字段上一个注解都没有。
///
/// 只服务写侧「注解来源随 access type 变」的用例：判定若只读 `Field` 上的 `@Column`，就看不到
/// getter 上的 `updatable = false`。
@Entity
@Table(name = "property_access_flags")
@Access(AccessType.PROPERTY)
public class PropertyAccessFlags {

    private Long id;

    private String label;

    private String lockedName;

    private PropertyContact contact;

    protected PropertyAccessFlags() {}

    public PropertyAccessFlags(Long id, String label, String lockedName, PropertyContact contact) {
        this.id = id;
        this.label = label;
        this.lockedName = lockedName;
        this.contact = contact;
    }

    @Id
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @Column(name = "label")
    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    @Column(name = "locked_name", updatable = false)
    public String getLockedName() {
        return lockedName;
    }

    public void setLockedName(String lockedName) {
        this.lockedName = lockedName;
    }

    @Embedded
    public PropertyContact getContact() {
        return contact;
    }

    public void setContact(PropertyContact contact) {
        this.contact = contact;
    }

    /// 同 access type 的可嵌入类型：列注解都写在 getter 上。
    @Embeddable
    @Access(AccessType.PROPERTY)
    public static class PropertyContact {

        private String city;

        public PropertyContact() {}

        public PropertyContact(String city) {
            this.city = city;
        }

        @Column(name = "city")
        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }
    }
}
