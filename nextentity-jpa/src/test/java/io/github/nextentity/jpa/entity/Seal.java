package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `Article#seal` 的对端：`@OneToOne` 的目标表。
///
/// 与 `@ManyToOne` 的区别在**唯一性约束**：`@OneToOne` 的关联列带唯一索引，Hibernate 对它的
/// 读写路径与 `@ManyToOne` 不完全相同，所以「跳过关联」要单独钉一次。
@Entity
@Table(name = "seal")
public class Seal {

    @Id
    private Long id;

    @Column(name = "code")
    private String code;

    public Seal() {}

    public Seal(Long id, String code) {
        this.id = id;
        this.code = code;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
