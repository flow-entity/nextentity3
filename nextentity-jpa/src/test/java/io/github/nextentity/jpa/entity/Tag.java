package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `Article#tags` 的对端：`@ManyToMany` 的目标表。
///
/// 单独建实体是为了钉住「跳过关联」在**连接表**形态下的后果：`@ManyToMany` 的关联不落在
/// 实体表的外键列上，而在 `article_tag` 连接表里，跳过语义与 `@OneToMany` 并不等价。
@Entity
@Table(name = "tag")
public class Tag {

    @Id
    private Long id;

    @Column(name = "label")
    private String label;

    public Tag() {}

    public Tag(Long id, String label) {
        this.id = id;
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
