package io.github.nextentity.jpa.entity;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/// `@Access(PROPERTY)` + **getter 归一化**的实体：映射声明全在 getter 上，`getLabel()` 对外
/// 呈现 `trim` 后的形态，`setLabel` 原样落到字段。
///
/// 只服务「同一实体的两条写路径必须取到同一个值」的用例：Hibernate 的脏检查 / UPDATE 语句按
/// access type 读 **getter**，而 `JpaPersistExecutor` 拼 `SET` 时走 `EntityAttribute#get`；
/// 后者若只读字段，同一个对象在两条路径下会落库两个不同的值（`"padded"` vs `"  padded  "`），
/// 且不抛任何异常。
@Entity
@Table(name = "normalized_property_access")
@Access(AccessType.PROPERTY)
public class NormalizedPropertyAccess {

    private Long id;

    private String label;

    private Long version;

    protected NormalizedPropertyAccess() {}

    public NormalizedPropertyAccess(Long id, String label, Long version) {
        this.id = id;
        this.label = label;
        this.version = version;
    }

    @Id
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    /// 归一化读：对外形态永远是 `trim` 后的字符串。
    @Column(name = "label")
    public String getLabel() {
        return label == null ? null : label.trim();
    }

    public void setLabel(String label) {
        this.label = label;
    }

    @Version
    @Column(name = "version")
    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
