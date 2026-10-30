package io.github.nextentity.core.builder.projection;

/// 包私有的 DTO：类本身、默认构造器与 `name()` 都不是 public。
///
/// 放在 `builder` 的子包里是刻意的——装配器在 `io.github.nextentity.core.builder`，从那个包
/// 反射调用这里的公开 setter 必须 `setAccessible(true)`，否则 `Method#invoke` 抛
/// `IllegalAccessException`。同包的 DTO（测试类自己的包）证明不了这件事。
@SuppressWarnings("unused")
class PackagePrivateDto {

    private String name;

    public void setName(String name) {
        this.name = name;
    }

    String name() {
        return name;
    }
}
