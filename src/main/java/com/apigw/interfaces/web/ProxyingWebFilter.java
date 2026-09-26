package com.apigw.interfaces.web;

import com.apigw.application.proxy.AccessLogService;
import com.apigw.application.proxy.RouteCatalog;
import com.apigw.domain.proxy.CompiledRoute;
import com.apigw.domain.proxy.HttpHeaderRules;
import com.apigw.domain.proxy.ProxyFailure;
import com.apigw.domain.proxy.RequestSnapshot;
import com.apigw.domain.proxy.UpstreamForwarder;
import com.apigw.domain.proxy.UpstreamUrls;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 转发链路的主过滤器，整条路在这里走完：
 *
 * 请求进来
 *   ① 分配 traceId、记「进来」一段账；
 *   ② 用内存路由表按条件找该走哪条（表已按稳定优先级排好，命中结果唯一确定）；
 *      一张都没命中 → 404 + GATEWAY_NO_ROUTE，明确告诉调用方是网关没找着路；
 *   ③ 请求类动作（已按顺序号排好）作用到发给上游的报文：补头覆盖、删头删除；
 *   ④ 打到上游；上游连不上→502、半天不吭声→504；
 *   ⑤ 上游响应回来，响应类动作按顺序号作用到回给调用方的报文，再交还调用方；
 *   ⑥ 记「回去」一段账：命中路由、上游、耗时、最终状态，与进来的一段同 traceId 可拼。
 *
 * 管理接口（/api/gateway/**）不走这条路，直接放行。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProxyingWebFilter implements WebFilter {

    /** 管理接口前缀：它后面挂着配置管理的 Controller，绝不能被当成待转发的业务请求。 */
    private static final String ADMIN_PREFIX = "/api/gateway/";
    private static final String ADMIN_EXACT = "/api/gateway";

    private final RouteCatalog catalog;
    private final UpstreamForwarder forwarder;
    private final GatewayErrorResponder errors;
    private final AccessLogService accessLog;

    public ProxyingWebFilter(RouteCatalog catalog,
                             UpstreamForwarder forwarder,
                             GatewayErrorResponder errors,
                             AccessLogService accessLog) {
        this.catalog = catalog;
        this.forwarder = forwarder;
        this.errors = errors;
        this.accessLog = accessLog;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        if (isAdminPath(path)) {
            return chain.filter(exchange);
        }

        String method = request.getMethod().name();
        String client = clientIp(request);
        // 调用方若自带 traceId 就沿用，便于跨系统串联；没有则网关生成一个
        String incomingTraceId = request.getHeaders().getFirst(HttpHeaderRules.TRACE_ID_HEADER);
        final String traceId = (incomingTraceId == null || incomingTraceId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : incomingTraceId;

        long startNanos = System.nanoTime();
        accessLog.logIn(traceId, method, path, client);

        AtomicInteger finalStatus = new AtomicInteger();
        // 结果分类在链路各节点显式写入，不能只看状态码——上游自己回的 4xx/5xx 仍是转发成功
        AtomicReference<String> outcome =
                new AtomicReference<>(AccessLogService.OUTCOME_GATEWAY_ERROR);
        return routeAndForward(exchange, path, traceId, finalStatus, outcome)
                .onErrorResume(e -> {
                    ProxyFailure failure = GatewayErrorResponder.classify(e);
                    if (failure == ProxyFailure.GATEWAY_ERROR) {
                        // 预期外的系统故障细节必须留在服务端，对外只说场面话
                        log.error("转发失败 traceId={} method={} path={}", traceId, method, path, e);
                    }
                    finalStatus.set(failure.status());
                    outcome.set(toOutcome(failure));
                    return errors.respond(exchange, failure, traceId);
                })
                .doFinally(sig -> accessLog.logOut(
                        traceId, method, path, client,
                        currentRouteNo(exchange), currentUpstream(exchange),
                        java.util.concurrent.TimeUnit.NANOSECONDS
                                .toMillis(System.nanoTime() - startNanos),
                        finalStatus.get(),
                        outcome.get()));
    }

    private Mono<Void> routeAndForward(ServerWebExchange exchange, String path,
                                       String traceId, AtomicInteger finalStatus,
                                       AtomicReference<String> outcome) {
        ServerHttpRequest request = exchange.getRequest();
        MultiValueMap<String, String> query = request.getQueryParams();
        RequestSnapshot snapshot = new RequestSnapshot(
                request.getMethod().name(), path, query, request.getHeaders());

        CompiledRoute route = catalog.select(snapshot);
        if (route == null) {
            finalStatus.set(ProxyFailure.NO_ROUTE.status());
            outcome.set(AccessLogService.OUTCOME_NO_ROUTE);
            return errors.respond(exchange, ProxyFailure.NO_ROUTE, traceId);
        }
        exchange.getAttributes().put(AccessKeys.ROUTE_NO, route.routeNo());
        exchange.getAttributes().put(AccessKeys.UPSTREAM, route.upstream());

        URI target = UpstreamUrls.build(route.upstream(), path, request.getURI().getRawQuery());
        return forwarder.forward(exchange, route, target, traceId)
                .doOnNext(status -> {
                    finalStatus.set(status);
                    // 拿到上游应答（哪怕是 4xx/5xx）就是一次成功的转发，错误是上游业务的
                    outcome.set(AccessLogService.OUTCOME_SUCCESS);
                })
                .then();
    }

    private boolean isAdminPath(String path) {
        return path.startsWith(ADMIN_PREFIX) || path.equals(ADMIN_EXACT);
    }

    private String clientIp(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null ? "-" : remote.getAddress().getHostAddress();
    }

    private String currentRouteNo(ServerWebExchange exchange) {
        Object v = exchange.getAttribute(AccessKeys.ROUTE_NO);
        return v == null ? null : v.toString();
    }

    private String currentUpstream(ServerWebExchange exchange) {
        Object v = exchange.getAttribute(AccessKeys.UPSTREAM);
        return v == null ? null : v.toString();
    }

    /** 网关失败类别 → 查账结果分类。 */
    private String toOutcome(ProxyFailure failure) {
        return switch (failure) {
            case NO_ROUTE -> AccessLogService.OUTCOME_NO_ROUTE;
            case UPSTREAM_UNAVAILABLE -> AccessLogService.OUTCOME_UPSTREAM_UNAVAILABLE;
            case UPSTREAM_TIMEOUT -> AccessLogService.OUTCOME_UPSTREAM_TIMEOUT;
            case GATEWAY_ERROR -> AccessLogService.OUTCOME_GATEWAY_ERROR;
        };
    }

    /** 放在 exchange 属性里的账本网。 */
    static final class AccessKeys {
        static final String ROUTE_NO = "apigw.matchedRouteNo";
        static final String UPSTREAM = "apigw.upstream";
    }
}
