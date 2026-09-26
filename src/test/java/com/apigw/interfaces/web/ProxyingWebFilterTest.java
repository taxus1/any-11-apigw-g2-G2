package com.apigw.interfaces.web;

import com.apigw.application.proxy.AccessLogService;
import com.apigw.application.proxy.RouteCatalog;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import com.apigw.infrastructure.proxy.ProxyProperties;
import com.apigw.infrastructure.proxy.WebClientUpstreamForwarder;
import com.apigw.infrastructure.store.RouteStore;
import com.apigw.support.RawHttpUpstream;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 转发链路端到端切片（不依赖 Redis）：真实 WebFilter + 真实 WebClient 转发 + 裸 HTTP 上游。
 *
 * 覆盖题目点：
 * - 路径前缀边界、多路由稳定定序、404「网关没找着路」与上游 404 的区分；
 * - 请求补头覆盖同名头、请求删头上游收不到；响应补/删头落到回给调用方的报文；
 * - 上游响应的 Content-Length / Connection 等不照抄（回包无长度头，走分块）；
 * - 上游连不上 502、半天不吭声 504，错误体是网关 JSON、带 X-Gateway-Error，不回上游原始页；
 * - 上游自身 500 原样透传，且不被记成网关错误；
 * - 管理接口不被转发；
 * - 热刷新：reload 后新路由立刻能走通。
 */
class ProxyingWebFilterTest {

    private RawHttpUpstream upstream;
    private RouteStore store;
    private RouteCatalog catalog;
    private WebTestClient web;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new RawHttpUpstream();
        store = mock(RouteStore.class);
        when(store.findAll()).thenReturn(Flux.fromIterable(routes()));
        catalog = new RouteCatalog(store);
        catalog.reload().block();

        ProxyProperties properties = new ProxyProperties();
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setResponseTimeout(Duration.ofSeconds(2));

        var forwarder = new WebClientUpstreamForwarder(properties);
        var filter = new ProxyingWebFilter(catalog, forwarder,
                new GatewayErrorResponder(new ObjectMapper()), new AccessLogService());

        // 链路里只有我们的转发过滤器；过滤器不匹配管理路径时会走到这个空下游
        org.springframework.web.server.WebHandler downstream = exchange -> Mono.empty();
        var filteringHandler =
                new org.springframework.web.server.handler.FilteringWebHandler(
                        downstream, List.of(filter));
        web = WebTestClient.bindToWebHandler(filteringHandler)
                .configureClient().codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    @AfterEach
    void tearDown() {
        upstream.close();
    }

    private List<GatewayRoute> routes() {
        String base = upstream.base();
        List<GatewayRoute> all = new ArrayList<>();

        all.add(route("order-dir", base,
                List.of(rule(RuleTypes.TYPE_PATH_PREFIX, null, "/order/", 1),
                        rule(RuleTypes.TYPE_METHOD, null, "GET", 2)),
                List.of(
                        // 补头：覆盖调用方自带同名头；删头：调用方塞了 X-Drop 也必须在上游消失
                        rule(RuleTypes.TYPE_REQ_ADD_HEADER, "X-Gw", "1", 1),
                        rule(RuleTypes.TYPE_REQ_REMOVE_HEADER, "X-Drop", null, 2),
                        rule(RuleTypes.TYPE_RESP_ADD_HEADER, "X-Trace", "t-1", 3),
                        rule(RuleTypes.TYPE_RESP_REMOVE_HEADER, "X-Debug", null, 4))));

        // POST 路由：路径 + 方法 + 头 + 查询参数四类条件同时满足才命中（独立前缀，不被其它规则遮蔽）
        all.add(route("order-post", base,
                List.of(rule(RuleTypes.TYPE_PATH_PREFIX, null, "/post-order/", 1),
                        rule(RuleTypes.TYPE_METHOD, null, "POST", 2),
                        rule(RuleTypes.TYPE_HEADER, "X-Caller", "web", 3),
                        rule(RuleTypes.TYPE_QUERY, "from", "cart", 4)),
                List.of(rule(RuleTypes.TYPE_RESP_ADD_HEADER, "X-Trace", "t-1", 1))));

        all.add(route("order-exact", base,
                List.of(rule(RuleTypes.TYPE_PATH_PREFIX, null, "/order", 1)),
                List.of()));

        // 上游带路径前缀：目标 URL 要拼成 /base/echo
        all.add(route("prefixed", base + "/base",
                List.of(rule(RuleTypes.TYPE_PATH_PREFIX, null, "/with-prefix/", 1)),
                List.of(rule(RuleTypes.TYPE_REQ_ADD_HEADER, "X-Via-Prefix", "yes", 1))));

        all.add(route("slow", base,
                List.of(rule(RuleTypes.TYPE_PATH_PREFIX, null, "/slow/", 1)), List.of()));

        all.add(route("dead", "http://127.0.0.1:" + deadPort(),
                List.of(rule(RuleTypes.TYPE_PATH_PREFIX, null, "/dead/", 1)), List.of()));

        all.add(route("up500", base,
                List.of(rule(RuleTypes.TYPE_PATH_PREFIX, null, "/up500/", 1)), List.of()));
        return all;
    }

    private GatewayRoute route(String no, String up,
                               List<GatewayRule> conds, List<GatewayRule> actions) {
        GatewayRoute r = GatewayRoute.create(no, no, up, 1, null);
        r.replaceRules(conds, actions);
        return r;
    }

    private GatewayRule rule(String type, String name, String value, int sort) {
        return GatewayRule.create(null, type, name, value, sort);
    }

    private static int deadPort() {
        try {
            return RawHttpUpstream.freeClosedPort();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void prefixBoundary_routesDistinguishTrailingSlash() {
        // /order 只命中不带尾斜杠的 order-exact，上游没有 /echo，回上游自己的 404
        web.get().uri("/order").exchange()
                .expectStatus().isNotFound()
                .expectBody()
                // 上游返回的 404 文本，区别于网关的 JSON 404
                .jsonPath("$.code").doesNotExist();

        // /order/abc 走 order-dir（前缀本体等长时编号字典序：dir<exact）
        web.get().uri("/order/echo").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("/order/echo");
    }

    @Test
    void upstreamBasePathPrefix_isJoined() {
        web.get().uri("/with-prefix/echo").exchange()
                .expectStatus().isOk()
                .expectBody()
                // 完整请求路径接在上游前缀后面，且不出现重复斜杠
                .jsonPath("$.path").isEqualTo("/base/with-prefix/echo")
                .jsonPath("$.headers['X-Via-Prefix']").isEqualTo("yes");
    }

    @Test
    void requestActions_overrideAndRemove_landOnUpstreamPacket() {
        web.get().uri("/order/echo")
                .header("X-Gw", "caller-wants-this")
                .header("X-Drop", "should-be-removed")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.headers['X-Gw']").isEqualTo("1")
                .jsonPath("$.headers['X-Drop']").doesNotExist();
    }

    @Test
    void responseActions_landOnClientPacket_andHopHeadersNotCopied() {
        web.get().uri("/order/echo").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Trace", "t-1")
                .expectHeader().doesNotExist("X-Debug")
                // 上游照报文给的内容长度，网关不能原样转发（重新写出后由 Netty 定分块）
                .expectHeader().doesNotExist("Content-Length")
                .expectBody()
                .jsonPath("$.path").isEqualTo("/order/echo");
    }

    @Test
    void postBody_isForwarded_andFindsRouteByHeaderAndQuery() {
        // 这条路由只在 POST + 指定头 + 指定查询参数同时满足时命中，验证四类条件的「且」
        String payload = "{\"item\":\"book\"}";
        web.post().uri("/post-order/echo-body?from=cart")
                .header("Content-Type", "application/json")
                .header("X-Caller", "web")
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Trace", "t-1")
                .expectBody().json(payload);

        // 缺一个头条件：不应走该前缀路由，回网关 404
        web.post().uri("/post-order/echo-body?from=cart")
                .header("Content-Type", "application/json")
                .bodyValue(payload)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals("X-Gateway-Error", "GATEWAY_NO_ROUTE");
    }

    @Test
    void noRoute_isGateway404Json_distinctFromUpstream404() {
        web.get().uri("/nothing/here").exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals("X-Gateway-Error", "GATEWAY_NO_ROUTE")
                .expectBody()
                .jsonPath("$.code").isEqualTo("GATEWAY_NO_ROUTE")
                .jsonPath("$.message").isEqualTo("网关没有匹配到可用路由")
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    void upstreamUnreachable_is502_gatewayJson() {
        web.get().uri("/dead/x").exchange()
                .expectStatus().isEqualTo(502)
                .expectHeader().valueEquals("X-Gateway-Error", "GATEWAY_UPSTREAM_UNAVAILABLE")
                .expectBody()
                .jsonPath("$.code").isEqualTo("GATEWAY_UPSTREAM_UNAVAILABLE");
    }

    @Test
    void upstreamSilent_is504_gatewayJson() {
        web.get().uri("/slow/slow").exchange()
                .expectStatus().isEqualTo(504)
                .expectHeader().valueEquals("X-Gateway-Error", "GATEWAY_UPSTREAM_TIMEOUT")
                .expectBody()
                .jsonPath("$.code").isEqualTo("GATEWAY_UPSTREAM_TIMEOUT");
    }

    @Test
    void upstreamOwn500_isPassedThrough_notRewrittenAsGatewayError() {
        web.get().uri("/up500/upstream-500").exchange()
                .expectStatus().isEqualTo(500)
                .expectHeader().doesNotExist("X-Gateway-Error")
                .expectBody(String.class)
                .value(b -> assertTrue(b.contains("upstream exploded"),
                        "上游的原始 500 页面应透传给调用方"));
    }

    @Test
    void adminApi_isNotProxied() {
        // 没有路由匹配 /api/gateway/routes；它必须被放行给管理 Controller，
        // 而不是被转发过滤器当成业务请求回 404。这里没有 Controller，会走到 404 但不带网关错误标记
        web.get().uri("/api/gateway/routes").exchange()
                .expectHeader().doesNotExist("X-Gateway-Error");
    }

    @Test
    void newRouteTakesEffectAfterReload_withoutRestart() {
        // 初始表没有 /fresh/，先走网关 404
        web.get().uri("/fresh/echo").exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals("X-Gateway-Error", "GATEWAY_NO_ROUTE");

        // 管理侧在 Redis 新增一条，转发侧重新拉表
        when(store.findAll()).thenReturn(Flux.fromIterable(java.util.stream.Stream
                .concat(routes().stream(), java.util.stream.Stream.of(
                        route("fresh", upstream.base(),
                                List.of(rule(RuleTypes.TYPE_PATH_PREFIX, null, "/fresh/", 1)),
                                List.of())))
                .toList()));
        catalog.reload().block();

        web.get().uri("/fresh/echo").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/fresh/echo");
    }
}
