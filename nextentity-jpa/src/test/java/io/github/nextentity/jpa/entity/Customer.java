package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// 测试用客户实体：同时有普通外键列 {@code orgId} 与关联 {@code org}。
///
/// 形状对齐 point-mall 的真实实体（`customer.get(Customer::getOrgId)` 与
/// `customer.get(Customer::getOrg).get(Organization::getName)` 两种调用都存在）。
@Entity
@Table(name = "customer")
public class Customer {

    @Id
    private Long id;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "name")
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization org;

    public Customer() {}

    public Customer(Long id, Long orgId, String name, Organization org) {
        this.id = id;
        this.orgId = orgId;
        this.name = name;
        this.org = org;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Organization getOrg() {
        return org;
    }

    public void setOrg(Organization org) {
        this.org = org;
    }
}
