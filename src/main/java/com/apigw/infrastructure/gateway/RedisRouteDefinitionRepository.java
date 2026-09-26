package com.apigw.infrastructure.gateway;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import com.apigw.infrastructure.store.RouteStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把存在 Redis 里的路由配置翻译成 Spring Cloud Gateway 的 {@link RouteDefinition}。
 *
 * 这层是「我们的业务模型」与「SCG 的运行时模型」之间的适配点：
 * - 业务侧只认 GatewayRoute / GatewayRule（条件 PATH_PREFIX、METHOD…；动作 REQ_ADD_HEADER…）；
 * - SCG 侧认 Predicate + Filter 的名字与参数（Path、Method、AddRequestHeader…）。
 *
 * 说明：实际转发由 {@code com.apigw.interfaces.web.ProxyingWebFilter} 主导——
 * 题目要求的前缀边界（/order/ 与 /order 不同）、补头覆盖同名头、响应内容长度重算等语义，
 * 内置谓词/过滤器给不了；本类保留作为两套模型的唯一翻译参考，并继续供相关单测约束
 * 「前缀 → PathPattern」的映射。改完配置的热刷新由 RouteCatalogRefresher 负责。
 */
@Slf4j
// 不作为 SCG 运行时路由仓库注册：实际转发由 interfaces.web.ProxyingWebFilter 主导。
// 本类保留「我们的模型 ↔ SCG 模型」唯一翻译点的地位，toPrefixPattern 仍有单测约束。
public class RedisRouteDefinitionRepository implements RouteDefinitionRepository {

    /** 把业务动作类型映射到 SCG 内置 GatewayFilterFactory 的名字。 */
    private static final Map<String, String> ACTION_TO_FILTER = Map.of(
            RuleTypes.TYPE_REQ_ADD_HEADER, "AddRequestHeader",
            RuleTypes.TYPE_REQ_REMOVE_HEADER, "RemoveRequestHeader",
            RuleTypes.TYPE_RESP_ADD_HEADER, "AddResponseHeader",
            RuleTypes.TYPE_RESP_REMOVE_HEADER, "RemoveResponseHeader");

    private final RouteStore routeStore;

    public RedisRouteDefinitionRepository(RouteStore routeStore) {
        this.routeStore = routeStore;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return routeStore.findAll()
                .filter(r -> r.getEnabled() != null && r.getEnabled() == 1)
                .filter(r -> !r.getConditions().isEmpty())
                .sort(Comparator.comparing(GatewayRoute::getRouteNo))
                .map(this::toRouteDefinition)
                .doOnNext(d -> log.debug("装载路由定义 id={} uri={} predicates={} filters={}",
                        d.getId(), d.getUri(), d.getPredicates().size(), d.getFilters().size()));
    }

    @Override
    public Mono<Void> save(Mono<RouteDefinition> route) {
        // 管理接口不走这里落库，统一走 RouteStore；这里只做只读仓库，
        // 避免出现「SCG 写一份、管理接口写一份」两条真源。
        return Mono.error(new UnsupportedOperationException(
                "路由请走管理接口 /api/gateway/routes 维护，不要直接写 RouteDefinitionRepository"));
    }

    @Override
    public Mono<Void> delete(Mono<String> routeId) {
        return Mono.error(new UnsupportedOperationException(
                "路由请走管理接口 /api/gateway/routes 维护，不要直接写 RouteDefinitionRepository"));
    }

    private RouteDefinition toRouteDefinition(GatewayRoute route) {
        RouteDefinition def = new RouteDefinition();
        // SCG 的路由 id 用业务编号，便于日志与排障对齐
        def.setId(route.getRouteNo());
        def.setUri(URI.create(route.getUpstream()));
        def.setOrder(0);

        List<PredicateDefinition> predicates = new ArrayList<>();
        for (GatewayRule c : RouteStore.sorted(route.getConditions())) {
            predicates.add(toPredicate(c));
        }
        def.setPredicates(predicates);

        List<FilterDefinition> filters = new ArrayList<>();
        for (GatewayRule a : RouteStore.sorted(route.getActions())) {
            filters.add(toFilter(a));
        }
        def.setFilters(filters);
        return def;
    }

    private PredicateDefinition toPredicate(GatewayRule rule) {
        switch (rule.getType()) {
            case RuleTypes.TYPE_PATH_PREFIX ->
                    // 「前缀」语义要翻译成 PathPattern 的 ** 通配：/order/ 与 /order 都匹配 /order 及其任意子路径。
                    // 直接把 /order/ 交给 SCG 的 Path 断言，只会按路径段精确匹配，/order/abc 是匹配不上的。
                    { return new PredicateDefinition("Path=" + toPrefixPattern(rule.getValue())); }
            case RuleTypes.TYPE_METHOD ->
                    { return new PredicateDefinition("Method=" + rule.getValue().toUpperCase(Locale.ROOT)); }
            case RuleTypes.TYPE_HEADER -> {
                return new PredicateDefinition("Header=" + rule.getName() + "," + rule.getValue());
            }
            case RuleTypes.TYPE_QUERY -> {
                return new PredicateDefinition("Query=" + rule.getName() + "," + rule.getValue());
            }
            default -> throw new IllegalStateException("未知条件类型：" + rule.getType());
        }
    }

    /** 把「路径前缀」归一成 PathPattern：去掉尾斜杠后统一追加 /**；已经是通配的保持原样。 */
    static String toPrefixPattern(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException("路径前缀不能为空");
        }
        String p = prefix.trim();
        if (p.contains("*")) {
            return p;
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.equals("/")) {
            return "/**";
        }
        return p + "/**";
    }

    private FilterDefinition toFilter(GatewayRule rule) {
        String filterName = ACTION_TO_FILTER.get(rule.getType());
        if (filterName == null) {
            throw new IllegalStateException("未知动作类型：" + rule.getType());
        }
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new IllegalStateException("动作缺头名：" + rule.getType());
        }
        String spec = rule.getValue() == null
                ? filterName + "=" + rule.getName()
                : filterName + "=" + rule.getName() + "," + rule.getValue();
        return new FilterDefinition(spec);
    }
}
