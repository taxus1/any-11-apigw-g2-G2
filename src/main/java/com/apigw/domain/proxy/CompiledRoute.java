package com.apigw.domain.proxy;

import java.util.List;

/**
 * 参与匹配/转发那一刻要用的一条路由：从领域聚合 {@code GatewayRoute} 编译而来，
 * 条件已经翻译成可直接判断的形态，动作已按方向拆成两组并按顺序号排好。
 *
 * 编译在路由表刷新时一次性完成，请求链路上只做判断，不做任何解析/排序。
 */
public final class CompiledRoute {

    /** 一条匹配条件：path 走 {@link PathPrefix}，其余三类是精确值比对。 */
    public record Condition(String type, PathPrefix path, String name, String value) {

        public boolean matches(RequestSnapshot req) {
            return switch (type) {
                case "PATH_PREFIX" -> path.matches(req.rawPath());
                case "METHOD" -> value.equalsIgnoreCase(req.method());
                // 头名按 HTTP 语义不区分大小写；头值精确相等（多值头任一个对上就算命中）
                case "HEADER" -> {
                    List<String> got = req.headers().get(name);
                    yield got != null && got.contains(value);
                }
                // 查询参数名按原样（区分大小写），值精确相等
                case "QUERY" -> {
                    List<String> got = req.queryParams().get(name);
                    yield got != null && got.contains(value);
                }
                default -> false;
            };
        }
    }

    private final String routeNo;
    private final String upstream;
    private final List<Condition> conditions;
    private final List<HeaderAction> requestActions;
    private final List<HeaderAction> responseActions;

    public CompiledRoute(String routeNo, String upstream,
                         List<Condition> conditions,
                         List<HeaderAction> requestActions,
                         List<HeaderAction> responseActions) {
        this.routeNo = routeNo;
        this.upstream = upstream;
        this.conditions = List.copyOf(conditions);
        this.requestActions = List.copyOf(requestActions);
        this.responseActions = List.copyOf(responseActions);
    }

    /** 一条路由上的多个条件是「且」：全部命中才算这条路由命中。 */
    public boolean matches(RequestSnapshot request) {
        for (Condition c : conditions) {
            if (!c.matches(request)) {
                return false;
            }
        }
        return true;
    }

    public String routeNo() {
        return routeNo;
    }

    public String upstream() {
        return upstream;
    }

    public List<Condition> conditions() {
        return conditions;
    }

    public List<HeaderAction> requestActions() {
        return requestActions;
    }

    public List<HeaderAction> responseActions() {
        return responseActions;
    }

    /** 条件里最长的路径前缀长度；没写路径条件的路由在定序时视为 0（让位于更具体的）。 */
    public int longestPathPrefixLength() {
        int best = 0;
        for (Condition c : conditions) {
            if (c.type.equals("PATH_PREFIX")) {
                best = Math.max(best, c.path.bodyLength());
            }
        }
        return best;
    }
}
