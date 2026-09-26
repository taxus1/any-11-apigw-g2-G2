package com.apigw.domain.proxy;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * 出站端口：把已经做完请求类动作的报文打到上游，并把上游响应（状态/头/身体）写给调用方。
 *
 * 端口放在领域侧，传输实现（{@code WebClientUpstreamForwarder}）在基础设施侧，
 * 链路编排只依赖这个端口，便于换实现、便于切片测试。
 */
public interface UpstreamForwarder {

    /**
     * 转发并回写。调用方保证进来前已完成：目标 URL 拼装、请求头按规则处理。
     * 上游正常应答时把状态/头/身体写给调用方，并返回实际下发的状态码；
     * 连不上/超时等以 {@link ProxyFailure} 对应的异常抛出，由链路统一收口。
     *
     * 注意：响应头/身体的回写发生在连接还开着的时候（exchangeToMono 内），
     * 保证身体没传完连接不会被提前归还。
     */
    Mono<Integer> forward(ServerWebExchange exchange,
                          CompiledRoute route,
                          URI target,
                          String traceId);
}
