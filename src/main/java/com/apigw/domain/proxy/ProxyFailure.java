package com.apigw.domain.proxy;

/**
 * 转发链路自己产生的、需要明确交代给调用方的失败。
 *
 * 三类失败必须让调用方一眼分得开，不能回成同一个东西：
 * - 没匹配到路由：404，告诉调用方「网关没找着路」，别误以为是后端服务挂了；
 * - 上游连不上：502；
 * - 上游半天不吭声（超时）：504。
 * 其余意料之外的故障统一 500，且 message 只说场面话，细节留在服务端日志里，
 * 不把内部堆栈、上游原始错误页抛给调用方。
 */
public enum ProxyFailure {

    NO_ROUTE(404, "GATEWAY_NO_ROUTE", "网关没有匹配到可用路由"),
    UPSTREAM_UNAVAILABLE(502, "GATEWAY_UPSTREAM_UNAVAILABLE", "上游服务暂时不可达"),
    UPSTREAM_TIMEOUT(504, "GATEWAY_UPSTREAM_TIMEOUT", "上游服务响应超时"),
    GATEWAY_ERROR(500, "GATEWAY_ERROR", "网关内部错误");

    private final int status;
    private final String code;
    private final String message;

    ProxyFailure(int status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public int status() {
        return status;
    }

    /** 机器可读的错误码，前端按它分流，不靠中文 message 猜。 */
    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
