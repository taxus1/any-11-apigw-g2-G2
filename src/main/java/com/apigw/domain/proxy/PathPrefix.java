package com.apigw.domain.proxy;

/**
 * 路径前缀匹配器，边界语义在这里钉死：
 *
 * - 规则 /order/（以斜杠结尾）：请求必须「落在它里面」——/order/abc、/order/ 命中，/order 不命中；
 * - 规则 /order（不以斜杠结尾）：请求等于 /order，或接在一个路径段边界后面（/order/abc）才命中；
 *   /orderabc 这种只是字符串前缀相同、越过了段边界的，绝不命中；
 * - 规则 /：命中一切。
 *
 * 大小写按常规 URL 语义：路径区分大小写（/Order 与 /order 不同）。
 *
 * 不用 startsWith("/order") 这种裸字符串判断，正是为了不让 /orderabc 误命中。
 */
public final class PathPrefix {

    private final String raw;
    /** 去掉结尾斜杠后的前缀本体，如 /order；根规则为 /。 */
    private final String body;
    /** 配置里是否以斜杠结尾——结尾与否的行为差别就靠它区分。 */
    private final boolean trailingSlash;

    private PathPrefix(String raw, String body, boolean trailingSlash) {
        this.raw = raw;
        this.body = body;
        this.trailingSlash = trailingSlash;
    }

    /**
     * 编译一条配置值。空值在配置保存时已被聚合拦下，这里再守一道。
     * 配置里允许不带开头的斜杠（order），编译时补齐；兼容误写的通配符，取首个 * 之前的部分当前缀。
     */
    public static PathPrefix compile(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("路径前缀不能为空");
        }
        String p = configured.trim();
        int star = p.indexOf('*');
        if (star >= 0) {
            p = p.substring(0, star);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        boolean trailing = p.endsWith("/");
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            p = "/";
        }
        return new PathPrefix(configured.trim(), p, trailing);
    }

    /**
     * 传入的应是请求行上的原始（未解码）路径，避免解码后再拼接造成二次编码。
     */
    public boolean matches(String requestPath) {
        if (requestPath == null) {
            return false;
        }
        if ("/".equals(body)) {
            return true;
        }
        if (trailingSlash) {
            // 以斜杠结尾：必须以 "/order/" 起头，/order 自身不算
            return requestPath.startsWith(body + "/");
        }
        // 不以斜杠结尾：要么正好等于 /order，要么从 /order/ 之后接续；/orderabc 不命中
        return requestPath.equals(body) || requestPath.startsWith(body + "/");
    }

    /** 用于路由定序：前缀本体越长越优先。 */
    public int bodyLength() {
        return "/".equals(body) ? 0 : body.length();
    }

    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }
}
