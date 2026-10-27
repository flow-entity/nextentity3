package io.github.nextentity.jdbc.dialect;

/// 方言追加窗口参数的唯一出口：顺序由方言决定（SQL Server 先 offset 后 limit）。
@FunctionalInterface
public interface SqlParameterSink {

    /// 追加一个绑定值。
    ///
    /// @param value 绑定值
    void append(Object value);
}
