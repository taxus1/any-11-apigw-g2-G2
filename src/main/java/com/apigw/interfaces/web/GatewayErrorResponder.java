package com.apigw.interfaces.web;

import com.apigw.domain.proxy.ProxyFailure;
import com.apigw.domain.proxy.ProxyTransportException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.apigw.domain.proxy.HttpHeaderRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关自己出错时的统一收口：没找着路 / 上游连不上 / 上游超时 / 网关内部故障，
 * 都在这里回一个说得清的 JSON，绝不把内部堆栈或上游的原始错误页抛给调用方。
 *
 * 四类失败靠 HTTP 状态码 + 错误码 + X-Gateway-Error 头三重区分，前端既能按码分流，
 * 也能拿 traceId 找服务端对账。
 */
@Slf4j
@Component
public class GatewayErrorResponder {

    /** 标记「这是网关产生的错误」，前端据此与后端服务自身的 4xx/5xx 区分。 */
    public static final String ERROR_MARKER = "X-Gateway-Error";

    private final ObjectMapper objectMapper;

    public GatewayErrorResponder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写出错误应答。已提交（响应头都发出去了，比如身体传到一半上游断了）时不能再改状态，
     * 只能结束，由访问日志记下这笔不完整的转发。
     */
    public Mono<Void> respond(ServerWebExchange exchange, ProxyFailure failure, String traceId) {
        var response = exchange.getResponse();
        if (response.isCommitted()) {
            log.debug("响应已提交，无法回写网关错误 traceId={} code={}", traceId, failure.code());
            return response.setComplete();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", failure.code());
        body.put("message", failure.message());
        body.put("traceId", traceId);

        response.setStatusCode(HttpStatus.valueOf(failure.status()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(ERROR_MARKER, failure.code());
        // 响应类动作只能作用于真正转发的响应；网关错误同样带上 traceId，便于拼回访问日志
        response.getHeaders().set(HttpHeaderRules.TRACE_ID_HEADER, traceId);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            log.error("序列化网关错误应答失败 traceId={}", traceId, e);
            bytes = ("{\"code\":\"" + failure.code() + "\",\"message\":\"gateway error\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        // 内容长度由我们按真实字节给准，避免错误页也踩长度对不上的坑
        response.getHeaders().set(HttpHeaders.CONTENT_LENGTH, Integer.toString(bytes.length));
        return response.writeWith(Mono.just(buffer));
    }

    /** 把转发过程中抛出的异常归类成 {@link ProxyFailure}。 */
    public static ProxyFailure classify(Throwable e) {
        if (e instanceof ProxyTransportException pte) {
            return pte.failure();
        }
        if (e instanceof ResponseStatusException rse
                && rse.getStatusCode().value() == 404) {
            // 正常情况下匹配不到已在过滤器内处理；走到这兜底成「没找着路」
            return ProxyFailure.NO_ROUTE;
        }
        return ProxyFailure.GATEWAY_ERROR;
    }
}
