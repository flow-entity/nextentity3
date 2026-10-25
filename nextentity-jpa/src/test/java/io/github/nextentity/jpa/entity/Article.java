package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/// 给「`update` 跳过关联属性」补齐最后两种关联形态：`@ManyToMany`（连接表 `article_tag`）
/// 与 `@OneToOne`（唯一外键列 `seal_id`）。
///
/// 已有的 `ShopOrder`（`@ManyToOne`）与 `VersionedLedger`（`@OneToMany`）覆盖的是"外键在实体表"
/// 与"子表持外键"两种形态；连接表形态是第三种：跳过它意味着连接表一行都不许动，既不能删、
/// 也不能重建。
@Entity
@Table(name = "article")
public class Article {

    @Id
    private Long id;

    @Column(name = "name")
    private String name;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seal_id")
    private Seal seal;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "article_tag",
            joinColumns = @JoinColumn(name = "article_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private List<Tag> tags = new ArrayList<>();

    public Article() {}

    public Article(Long id, String name, Seal seal, List<Tag> tags) {
        this.id = id;
        this.name = name;
        this.seal = seal;
        this.tags = tags;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Seal getSeal() {
        return seal;
    }

    public void setSeal(Seal seal) {
        this.seal = seal;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }
}
