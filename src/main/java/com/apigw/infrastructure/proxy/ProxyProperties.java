package com.apigw.infrastructure.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 转发链路参数。默认值偏保守，需要时用 apigw.proxy.* 覆盖。
 */
@ConfigurationProperties(prefix = "apigw.proxy")
public class ProxyProperties {

    /** 与上游建连（含从连接池取连接）的超时：连不上别让调用方干等。 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** 上游应答超时：发出请求后多久内必须拿到响应头，超时回 504。 */
    private Duration responseTimeout = Duration.ofSeconds(10);

    /** 路由表定时兜底刷新间隔（毫秒）。 */
    private long routeRefreshMs = 3000;

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public long getRouteRefreshMs() {
        return routeRefreshMs;
    }

    public void setRouteRefreshMs(long routeRefreshMs) {
        this.routeRefreshMs = routeRefreshMs;
    }
}
