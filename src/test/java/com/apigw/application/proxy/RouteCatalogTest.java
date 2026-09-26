package com.apigw.application.proxy;

import com.apigw.domain.proxy.CompiledRoute;
import com.apigw.domain.proxy.HeaderAction;
import com.apigw.domain.proxy.RequestSnapshot;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import com.apigw.infrastructure.store.RouteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 路由表的编译、热更新与「多条都命中」时的稳定定序，不需要 Redis（store 用 mock）。
 */
class RouteCatalogTest {

    private RouteStore store;
    private RouteCatalog catalog;

    @BeforeEach
    void setUp() {
        store = mock(RouteStore.class);
        catalog = new RouteCatalog(store);
    }

    private GatewayRoute route(String no, int enabled, List<GatewayRule> conds, List<GatewayRule> actions) {
        GatewayRoute r = GatewayRoute.create(no, no, "http://up-" + no + ":8080", enabled, null);
        r.replaceRules(conds, actions);
        return r;
    }

    private GatewayRule path(String prefix, int sort) {
        return GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, prefix, sort);
    }

    private RequestSnapshot get(String p) {
        return new RequestSnapshot("GET", p, new LinkedMultiValueMap<>(), new HttpHeaders());
    }

    @Test
    void reload_picksUpChangesWithoutRestart() {
        when(store.findAll()).thenReturn(Flux.fromIterable(List.of()));
        catalog.reload().block();
        assertNull(catalog.select(get("/new/abc")));

        // 模拟管理接口在 Redis 里新增了一条：重新拉取后立刻可命中（不重启）
        when(store.findAll()).thenReturn(Flux.fromIterable(List.of(
                route("new", 1, List.of(path("/new/", 1)), List.of()))));
        catalog.reload().block();

        CompiledRoute hit = catalog.select(get("/new/abc"));
        assertEquals("new", hit.routeNo());
    }

    @Test
    void disabledAndConditionlessRoutes_areExcluded() {
        when(store.findAll()).thenReturn(Flux.fromIterable(List.of(
                route("off", 0, List.of(path("/a/", 1)), List.of()),
                route("nocond", 1, List.of(), List.of()))));
        catalog.reload().block();

        assertNull(catalog.select(get("/a/x")));
        assertNull(catalog.select(get("/anything")));
    }

    @Test
    void longerPathPrefixWins_thenMoreConditions_thenRouteNo() {
        when(store.findAll()).thenReturn(Flux.fromIterable(List.of(
                route("z-root", 1, List.of(path("/", 1)), List.of()),
                // 同前缀 /order、同条件数：编号字典序，a- 先于 b-
                route("b-exact", 1, List.of(path("/order", 1)), List.of()),
                route("a-dir", 1, List.of(path("/order/", 1)), List.of()))));
        catalog.reload().block();

        // /order：只有 b-exact（无尾斜杠规则）能命中
        assertEquals("b-exact", catalog.select(get("/order")).routeNo());
        // /order/abc：a-dir 与 b-exact 都命中（前缀本体等长、条件数相同），编号小的 a-dir 稳定胜出
        assertEquals("a-dir", catalog.select(get("/order/abc")).routeNo());
        // /other：只有根前缀命中
        assertEquals("z-root", catalog.select(get("/other")).routeNo());
        // /orderabc：段边界没过，两条 /order* 都不命中，落到根
        assertEquals("z-root", catalog.select(get("/orderabc")).routeNo());
    }

    @Test
    void moreConditionsWin_whenPrefixEqual() {
        GatewayRule methodGet =
                GatewayRule.create(null, RuleTypes.TYPE_METHOD, null, "GET", 2);
        when(store.findAll()).thenReturn(Flux.fromIterable(List.of(
                route("one-cond", 1, List.of(path("/a/", 1)), List.of()),
                route("two-cond", 1, List.of(path("/a/", 1), methodGet), List.of()))));
        catalog.reload().block();

        // 前缀等长时，条件更多、约束更具体的路由胜出
        assertEquals("two-cond", catalog.select(get("/a/x")).routeNo());
    }

    @Test
    void actions_areSplitByDirectionAndSorted() {
        when(store.findAll()).thenReturn(Flux.fromIterable(List.of(
                route("act", 1, List.of(path("/a/", 1)), List.of(
                        GatewayRule.create(null, RuleTypes.TYPE_RESP_ADD_HEADER, "X-R", "r", 3),
                        GatewayRule.create(null, RuleTypes.TYPE_REQ_REMOVE_HEADER, "X-K", null, 2),
                        GatewayRule.create(null, RuleTypes.TYPE_REQ_ADD_HEADER, "X-K", "v", 1))))));
        catalog.reload().block();

        CompiledRoute r = catalog.select(get("/a/x"));
        // 请求组按顺序号：先补 X-K(1) 再删 X-K(2)
        assertEquals(List.of(1, 2), r.requestActions().stream().map(HeaderAction::sortNo).toList());
        // 响应方向的动作绝不混进请求组
        assertEquals(1, r.responseActions().size());
        assertEquals("X-R", r.responseActions().get(0).name());
        assertEquals(2, r.requestActions().size());
    }
}
