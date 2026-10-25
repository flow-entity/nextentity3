package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/// 第四层可嵌入类型：给 `location.coordinates.datum.code` 这条四段路径提供最内一层。
///
/// 三层嵌套路段的递归在 `JpaPaths.navigate` 里是同一段代码，但**前缀缓存**（`prefixes`）与
/// "每一层都判一次是不是关联"这两件事在第四层才暴露出复用问题：前三层早就被前序路径填满，
/// 第四层是唯一一个需要新建前缀项的地方。
@Embeddable
public class Datum {

    @Column(name = "datum_code")
    private String code;

    public Datum() {}

    public Datum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
