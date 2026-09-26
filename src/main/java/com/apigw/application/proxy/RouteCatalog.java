package com.apigw.application.proxy;

import com.apigw.domain.proxy.CompiledRoute;
import com.apigw.domain.proxy.HeaderAction;
import com.apigw.domain.proxy.PathPrefix;
import com.apigw.domain.proxy.RequestSnapshot;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import com.apigw.infrastructure.store.RouteStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 转发链路使用的内存路由表。
 *
 * 请求匹配只读本表，不每次都去打 Redis；管理接口改完配置后由 {@link RouteCatalogRefresher}
 * 触发 {@link #reload()}，新路由立刻能走通，不需要重启（另有定时轮询兜底多实例）。
 *
 * 重载重入整张表：一次拉取成功才整体替换；中途出错沿用旧表，绝不让一次 Redis 抖动
 * 把在跑的路由表清空。
 */
@Slf4j
@Component
public class RouteCatalog {

    /** 稳定的路由先后：最长路径前缀 → 条件数多者 → 编号字典序。 */
    private static final Comparator<CompiledRoute> PRIORITY = Comparator
            .comparingInt(CompiledRoute::longestPathPrefixLength).reversed()
            .thenComparing(r -> r.conditions().size(), Comparator.reverseOrder())
            .thenComparing(CompiledRoute::routeNo);

    private final RouteStore routeStore;
    private volatile List<CompiledRoute> routes = List.of();

    public RouteCatalog(RouteStore routeStore) {
        this.routeStore = routeStore;
    }

    /**
     * 从 Redis 重新拉全量路由并编译替换。
     *
     * @return 编译后的路由条数；拉取/编译失败时保留旧表并抛错，由刷新器决定重试
     */
    public Mono<Integer> reload() {
        return routeStore.findAll()
                .filter(r -> r.getEnabled() != null && r.getEnabled() == 1)
                // 没有任何匹配条件的路由无法判断命中与否，不参与转发（与装载侧既有约定一致）
                .filter(r -> !r.getConditions().isEmpty())
                .map(RouteCatalog::compile)
                .collectSortedList(PRIORITY)
                .doOnNext(list -> {
                    this.routes = list;
                    log.info("路由表已重载：启用路由 {} 条（{}）", list.size(),
                            list.stream().map(CompiledRoute::routeNo)
                                    .reduce((a, b) -> a + "," + b).orElse("-"));
                })
                .map(List::size);
    }

    /**
     * 按请求找路由：返回第一张全部条件都命中的表。表本身已按 {@link #PRIORITY} 排好，
     * 所以同样的请求永远走同一条，不会时此时彼；一张都没命中返回 null。
     */
    public CompiledRoute select(RequestSnapshot request) {
        for (CompiledRoute route : routes) {
            if (route.matches(request)) {
                return route;
            }
        }
        return null;
    }

    public int size() {
        return routes.size();
    }

    /** 把一条领域配置编译成运行时形态；条件翻译、动作按方向分组并排好顺序号。 */
    static CompiledRoute compile(GatewayRoute r) {
        List<CompiledRoute.Condition> conditions = new ArrayList<>();
        for (GatewayRule c : RouteStore.sorted(r.getConditions())) {
            conditions.add(compileCondition(c));
        }

        List<HeaderAction> req = new ArrayList<>();
        List<HeaderAction> resp = new ArrayList<>();
        for (GatewayRule a : RouteStore.sorted(r.getActions())) {
            HeaderAction action = switch (a.getType()) {
                case RuleTypes.TYPE_REQ_ADD_HEADER ->
                        HeaderAction.add(a.getName(), a.getValue(), a.getSortNo());
                case RuleTypes.TYPE_REQ_REMOVE_HEADER ->
                        HeaderAction.remove(a.getName(), a.getSortNo());
                case RuleTypes.TYPE_RESP_ADD_HEADER ->
                        HeaderAction.add(a.getName(), a.getValue(), a.getSortNo());
                case RuleTypes.TYPE_RESP_REMOVE_HEADER ->
                        HeaderAction.remove(a.getName(), a.getSortNo());
                default -> throw new IllegalStateException("未知动作类型：" + a.getType());
            };
            // 方向由类型决定：请求类动作绝不能进响应组，反之亦然
            if (RuleTypes.STAGE_RESPONSE.equals(a.getStage())) {
                resp.add(action);
            } else {
                req.add(action);
            }
        }
        return new CompiledRoute(r.getRouteNo(), r.getUpstream(), conditions, req, resp);
    }

    private static CompiledRoute.Condition compileCondition(GatewayRule c) {
        return switch (c.getType()) {
            case RuleTypes.TYPE_PATH_PREFIX ->
                    new CompiledRoute.Condition(RuleTypes.TYPE_PATH_PREFIX,
                            PathPrefix.compile(c.getValue()), null, null);
            case RuleTypes.TYPE_METHOD ->
                    new CompiledRoute.Condition(RuleTypes.TYPE_METHOD, null, null,
                            c.getValue().toUpperCase(Locale.ROOT));
            case RuleTypes.TYPE_HEADER ->
                    new CompiledRoute.Condition(RuleTypes.TYPE_HEADER, null, c.getName(), c.getValue());
            case RuleTypes.TYPE_QUERY ->
                    new CompiledRoute.Condition(RuleTypes.TYPE_QUERY, null, c.getName(), c.getValue());
            default -> throw new IllegalStateException("未知条件类型：" + c.getType());
        };
    }
}
