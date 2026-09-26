package com.apigw.domain.proxy;

/**
 * 到上游这一跳出的传输类故障，由基础设施层翻译好分类后抛出，
 * 链路统一按 {@link #failure()} 给调用方回不同的错误，不让连不上和超时混成一个。
 */
public class ProxyTransportException extends RuntimeException {

    private final transient ProxyFailure failure;

    public ProxyTransportException(ProxyFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public ProxyFailure failure() {
        return failure;
    }
}
