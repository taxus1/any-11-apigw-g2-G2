package com.apigw.domain.proxy;

import java.net.URI;

/**
 * 上游目标 URL 的拼装：上游基址（scheme://host[:port]，可带路径前缀）+ 原始请求路径 + 查询串。
 *
 * 路径/查询串都取自请求行上的原始形态（百分号编码保留），不在网关这一层解码再编码，
 * 避免空格、中文等字符被二次编码。
 */
public final class UpstreamUrls {

    private UpstreamUrls() {
    }

    /**
     * @param upstream 配置里的上游地址，允许带路径前缀，如 http://svc/base
     * @param rawPath  请求路径（不含查询串），如 /order/abc
     * @param rawQuery 查询串（不含 ?），可为 null
     */
    public static URI build(String upstream, String rawPath, String rawQuery) {
        URI base = URI.create(upstream);
        String path = joinPath(base.getRawPath(), rawPath);
        String query = mergeQuery(base.getRawQuery(), rawQuery);
        // 各部分都已是编码后的原始形态，直接拼字符串，绝不能再过一遍多参 URI 构造器
        // （那会把 % 编成 %25，造成 %E4 变 %25E4 的二次编码）
        StringBuilder sb = new StringBuilder();
        sb.append(base.getScheme()).append("://").append(base.getRawAuthority());
        sb.append(path);
        if (query != null && !query.isEmpty()) {
            sb.append('?').append(query);
        }
        return URI.create(sb.toString());
    }

    /** 基址路径前缀与请求路径拼接：两者都是 / 开头时去掉重复的斜杠。 */
    static String joinPath(String basePath, String requestPath) {
        String p = requestPath == null || requestPath.isEmpty() ? "/" : requestPath;
        if (basePath == null || basePath.isEmpty() || "/".equals(basePath)) {
            return p;
        }
        String b = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        return b + (p.startsWith("/") ? p : "/" + p);
    }

    /** 上游基址自带的查询参数与本次请求的参数合并；同名时以后者为准，后者为空则保留前者。 */
    static String mergeQuery(String baseQuery, String requestQuery) {
        if (requestQuery == null || requestQuery.isEmpty()) {
            return baseQuery;
        }
        if (baseQuery == null || baseQuery.isEmpty()) {
            return requestQuery;
        }
        return baseQuery + "&" + requestQuery;
    }
}
