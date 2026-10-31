package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/// 二级可嵌入类型：让 {@link LockedAddress} 里再嵌一层，验证写侧展开是递归的
/// （`address.detail.code` 可写、`address.detail.secret` 只读）。
@Embeddable
public class AddressDetail {

    @Column(name = "detail_code")
    private String code;

    @Column(name = "detail_secret", updatable = false)
    private String secret;

    public AddressDetail() {}

    public AddressDetail(String code, String secret) {
        this.code = code;
        this.secret = secret;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}
