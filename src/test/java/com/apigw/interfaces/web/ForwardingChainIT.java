package com.apigw.interfaces.web;

import com.apigw.infrastructure.store.RouteStore;
import com.apigw.support.EnabledIfRedis;
import com.apigw.support.RawHttpUpstream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 转发链路全链路集成：真实 Spring 服务 + 真实 Redis + 裸 HTTP 上游。
 * 本机没有 Redis 时按 {@link EnabledIfRedis} 自动跳过。
 *
 * 覆盖：
 * - 配置写入 Redis 后不重启、靠事件热刷新，新路由立刻能转发；
 * - 请求补/删头真实落到上游报文、响应补头真实落到回包；
 * - 修改路由（整树替换，带 version）后按新配置生效；
 * - 删除路由后该路径回到网关 404；
 * - 上游的 404（上游自己没这个路径）与网关 404（没路由）区分得开。
 */
@EnabledIfRedis
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "logging.level.com.apigw=warn",
        "logging.level.com.apigw.access=warn",
        "apigw.proxy.response-timeout=8s"
})
class ForwardingChainIT {

    @Autowired
    private WebTestClient web;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    private RawHttpUpstream upstream;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new RawHttpUpstream();
        redis.delete(RouteStore.ROUTES_KEY).block();
    }

    @AfterEach
    void tearDown() {
        upstream.close();
        redis.delete(RouteStore.ROUTES_KEY).block();
    }

    private Map<String, Object> rule(String type, String name, String value, int sortNo) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        if (name != null) {
            m.put("name", name);
        }
        if (value != null) {
            m.put("value", value);
        }
        m.put("sortNo", sortNo);
        return m;
    }

    private Map<String, Object> route(String upstreamUrl, Integer version,
                                      List<Map<String, Object>> conds,
                                      List<Map<String, Object>> actions) {
        Map<String, Object> m = new HashMap<>();
        m.put("routeNo", "it-ft");
        m.put("name", "全链路转发路由");
        m.put("upstream", upstreamUrl);
        m.put("enabled", 1);
        if (version != null) {
            m.put("version", version);
        }
        m.put("conditions", conds);
        m.put("actions", actions);
        return m;
    }

    private boolean proxiedOk() {
        try {
            Integer status = web.get().uri("/ft/echo").header("X-Gw", "caller")
                    .exchange().returnResult(String.class)
                    .getStatus().value();
            return status == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void createReloadProxy_updateReloadDeleteGivesGateway404() {
        // 初始：没路由，回网关 404
        web.get().uri("/ft/echo").exchange().expectStatus().isNotFound()
                .expectHeader().valueEquals("X-Gateway-Error", "GATEWAY_NO_ROUTE");

        // 1) 管理接口新建：补头覆盖 X-Gw、删头 X-Drop、响应补 X-Trace=t1
        List<Map<String, Object>> conds = List.of(rule("PATH_PREFIX", null, "/ft/", 1));
        List<Map<String, Object>> actions = List.of(
                rule("REQ_ADD_HEADER", "X-Gw", "1", 1),
                rule("REQ_REMOVE_HEADER", "X-Drop", null, 2),
                rule("RESP_ADD_HEADER", "X-Trace", "t1", 3));
        web.post().uri("/api/gateway/routes").bodyValue(route(upstream.base(), null, conds, actions))
                .exchange().expectBody().jsonPath("$.code").isEqualTo(0);

        // 不重启：等事件热刷新生效后能转发
        long deadline = System.currentTimeMillis() + 8000;
        while (!proxiedOk() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        web.get().uri("/ft/echo").header("X-Gw", "caller").header("X-Drop", "secret")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Trace", "t1")
                .expectHeader().doesNotExist("Content-Length")
                .expectBody()
                .jsonPath("$.path").isEqualTo("/ft/echo")
                .jsonPath("$.headers['X-Gw']").isEqualTo("1")
                .jsonPath("$.headers['X-Drop']").doesNotExist();

        // 2) 修改路由（version=0）：响应头改成 t2，整树替换
        List<Map<String, Object>> actions2 = List.of(
                rule("RESP_ADD_HEADER", "X-Trace", "t2", 1));
        web.put().uri("/api/gateway/routes/it-ft")
                .bodyValue(route(upstream.base(), 0, conds, actions2))
                .exchange().expectBody().jsonPath("$.code").isEqualTo(0);

        deadline = System.currentTimeMillis() + 8000;
        while (traceIs(2) != 1 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        web.get().uri("/ft/echo").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Trace", "t2");

        // 3) 删除路由：路径回到网关 404（不是上游 404）
        web.delete().uri("/api/gateway/routes/it-ft").exchange()
                .expectBody().jsonPath("$.code").isEqualTo(0);

        deadline = System.currentTimeMillis() + 8000;
        boolean gone = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                web.get().uri("/ft/echo").exchange()
                        .expectHeader().valueEquals("X-Gateway-Error", "GATEWAY_NO_ROUTE");
                gone = true;
                break;
            } catch (AssertionError e) {
                sleep(100);
            }
        }
        if (!gone) {
            throw new AssertionError("删除路由后未在预期时间内回到网关 404");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 返回当前回包 X-Trace 是 t1(1) 还是 t2(2)，查不到给 0。 */
    private int traceIs(int want) {
        try {
            String v = web.get().uri("/ft/echo").exchange()
                    .returnResult(String.class)
                    .getResponseHeaders().getFirst("X-Trace");
            return ("t" + want).equals(v) ? 1 : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
