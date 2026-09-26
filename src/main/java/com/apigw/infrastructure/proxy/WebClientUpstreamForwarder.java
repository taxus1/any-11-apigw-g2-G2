package com.apigw.infrastructure.proxy;

import com.apigw.domain.proxy.CompiledRoute;
import com.apigw.domain.proxy.HttpHeaderRules;
import com.apigw.domain.proxy.ProxyFailure;
import com.apigw.domain.proxy.ProxyTransportException;
import com.apigw.domain.proxy.UpstreamForwarder;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.ConnectException;
import java.net.URI;
import java.util.Set;

/**
 * 用 {@link WebClient}（Reactor Netty）把请求打到上游，并在响应连接还开着的时候把
 * 状态/头/身体回写给调用方。
 *
 * 关键点：
 * - 响应头先按 {@link HttpHeaderRules#prepareClient} 处理（动作按顺序执行、
 *   hop-by-hop 与 Content-Length/Transfer-Encoding 一律不照抄），再提交；
 *   长度交给 Netty 按真实写出的字节走分块，绝不可能「头里说 100、实际 80」；
 * - 不跟随上游重定向（3xx 连 Location 原样回给调用方，由调用方决定）；
 * - 建连/响应超时各自区分，前者 502、后者 504。
 */
@Slf4j
@Component
public class WebClientUpstreamForwarder implements UpstreamForwarder {

    /** 这些方法通常带身体；其余方法只有真的带了内容头时才转发身体。 */
    private static final Set<HttpMethod> BODY_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);

    private final WebClient webClient;

    public WebClientUpstreamForwarder(ProxyProperties properties) {
        // 身体是流式直通、不落内存，但仍把聚合缓冲上限放开，避免有组件想聚合时被默认 256K 卡住
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) properties.getConnectTimeout().toMillis())
                // 发完请求到响应头的最长等待；读超时按同样的上限保护身体阶段
                .responseTimeout(properties.getResponseTimeout())
                .doOnConnected(conn -> conn
                        .addHandlerLast(new io.netty.handler.timeout.ReadTimeoutHandler(
                                (int) properties.getResponseTimeout().toSeconds())))
                .followRedirect(false)
                // 默认不主动加 Accept-Encoding: gzip，调用方要压缩自己带
                .compress(false);

        this.webClient = WebClient.builder()
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public Mono<Integer> forward(ServerWebExchange exchange, CompiledRoute route,
                                 URI target, String traceId) {
        var request = exchange.getRequest();
        HttpHeaders upstreamHeaders =
                HttpHeaderRules.prepareUpstream(request.getHeaders(), route.requestActions(), traceId);

        WebClient.RequestBodySpec spec = webClient
                .method(HttpMethod.valueOf(request.getMethod().name()))
                .uri(target)
                .headers(h -> h.addAll(upstreamHeaders));

        if (hasBody(request.getMethod(), upstreamHeaders)) {
            spec.body(BodyInserters.fromDataBuffers(request.getBody()));
        }

        return spec.exchangeToMono(response -> {
            int status = response.statusCode().value();
            HttpHeaders clientHeaders =
                    HttpHeaderRules.prepareClient(response.headers().asHttpHeaders(),
                            route.responseActions());
            // 响应类动作可以覆盖我们的 traceId；没覆盖就用本链路的
            if (!clientHeaders.containsKey(HttpHeaderRules.TRACE_ID_HEADER)) {
                clientHeaders.set(HttpHeaderRules.TRACE_ID_HEADER, traceId);
            }

            exchange.getResponse().setStatusCode(response.statusCode());
            exchange.getResponse().getHeaders().putAll(clientHeaders);

            // body 在 exchangeToMono 里写出：此时上游连接还开着，身体没写完不会被提前归还
            Mono<Void> written = exchange.getResponse()
                    .writeWith(response.bodyToFlux(DataBuffer.class));
            return written.thenReturn(status);
        }).onErrorMap(Exception.class, e -> classify(e, route));
    }

    /**
     * 把传输异常翻译成三类网关失败。不把上游原始异常/错误页透出去，细节进日志。
     */
    private Throwable classify(Exception e, CompiledRoute route) {
        if (e instanceof ProxyTransportException) {
            return e;
        }
        // WebClient 会把真实故障包成 WebClientRequestException，得沿 cause 链认出底层原因
        boolean timeout = hasCauseOf(e, java.util.concurrent.TimeoutException.class,
                ReadTimeoutException.class);
        if (timeout || containsName(e, "Timeout", "TimedOut")) {
            log.warn("上游响应超时 routeNo={} upstream={} reason={}",
                    route.routeNo(), route.upstream(), e.toString());
            return new ProxyTransportException(ProxyFailure.UPSTREAM_TIMEOUT,
                    "上游响应超时", e);
        }
        if (e instanceof ConnectException
                || hasCauseOf(e, ConnectException.class)
                || containsName(e, "Connect", "Acquire", "Connection")) {
            log.warn("上游不可达 routeNo={} upstream={} reason={}",
                    route.routeNo(), route.upstream(), e.toString());
            return new ProxyTransportException(ProxyFailure.UPSTREAM_UNAVAILABLE,
                    "上游不可达", e);
        }
        log.error("转发过程中发生未预期错误 routeNo={} upstream={}",
                route.routeNo(), route.upstream(), e);
        return new ProxyTransportException(ProxyFailure.GATEWAY_ERROR, "网关内部错误", e);
    }

    private static boolean hasCauseOf(Throwable e, Class<?>... types) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            for (Class<?> t : types) {
                if (t.isInstance(c)) {
                    return true;
                }
            }
            if (c == c.getCause()) {
                break;
            }
        }
        return false;
    }

    private static boolean containsName(Throwable e, String... fragments) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            String n = c.getClass().getName();
            for (String f : fragments) {
                if (n.contains(f)) {
                    return true;
                }
            }
            if (c == c.getCause()) {
                break;
            }
        }
        return false;
    }

    private boolean hasBody(HttpMethod method, HttpHeaders headers) {
        if (BODY_METHODS.contains(method)) {
            return true;
        }
        return headers.getContentLength() > 0
                || headers.getFirst(HttpHeaders.TRANSFER_ENCODING) != null;
    }
}
