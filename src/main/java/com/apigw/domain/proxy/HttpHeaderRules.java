package com.apigw.domain.proxy;

import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 请求/响应报文在网关两端之间搬运时的头部规则。两个方向的规则不一样，
 * 在这里一处钉死，请求方向的动作绝不作用到响应，反之亦然。
 *
 * 两个必须处理对的点：
 * 1. hop-by-hop 头只对「当前这一跳」有意义，转发时必须摘掉：Connection、Keep-Alive、
 *    TE、Trailer、Upgrade、Proxy-*，以及 Connection 头里点名的那些头；
 * 2. Content-Length / Transfer-Encoding 跟具体报文的身体绑死。上游回的内容长度、
 *    传输编码不能原样照抄回调用方（动作改过头、或身体经过重新写出后容易对不上），
 *    响应方向一律摘掉，交给框架按实际写出的字节重算（分块传输）。
 */
public final class HttpHeaderRules {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final TreeSet<String> HOP_BY_HOP = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        HOP_BY_HOP.addAll(List.of(
                "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
                "te", "trailer", "upgrade", "proxy-connection"));
    }

    private HttpHeaderRules() {
    }

    /**
     * 构造发给上游的请求头：
     * 1. 复制调用方头，摘掉 hop-by-hop、Host（由上游 URL 决定）、Transfer-Encoding（分块与否由客户端重定）；
     * 2. 放入链路 traceId；
     * 3. 按顺序号执行该路由的请求类动作——补头是覆盖，删头是删除。
     * Content-Length 与实际请求体一致，原样保留。
     */
    public static HttpHeaders prepareUpstream(HttpHeaders incoming,
                                              List<HeaderAction> requestActions,
                                              String traceId) {
        HttpHeaders out = copyPassThrough(incoming);
        // Host 由 WebClient 按目标 URL 重新给出；TE 是否分块也由它按身体决定
        out.remove(HttpHeaders.HOST);
        removeHopByHopNamed(incoming, out);
        out.remove(HttpHeaders.TRANSFER_ENCODING);

        out.set(TRACE_ID_HEADER, traceId);
        apply(out, requestActions);
        return out;
    }

    /**
     * 构造回给调用方的响应头：
     * 1. 复制上游响应头，摘掉 hop-by-hop；
     * 2. 按顺序号执行响应类动作；
     * 3. 无论上游给了什么，Content-Length / Transfer-Encoding 都不照抄——
     *    身体由网关重新写出，分块与否和长度交给 Netty 按真实字节决定。
     */
    public static HttpHeaders prepareClient(HttpHeaders upstream,
                                            List<HeaderAction> responseActions) {
        HttpHeaders out = copyPassThrough(upstream);
        removeHopByHopNamed(upstream, out);
        apply(out, responseActions);
        out.remove(HttpHeaders.CONTENT_LENGTH);
        out.remove(HttpHeaders.TRANSFER_ENCODING);
        return out;
    }

    private static HttpHeaders copyPassThrough(HttpHeaders in) {
        HttpHeaders out = new HttpHeaders();
        in.forEach(out::addAll);
        out.remove(HttpHeaders.CONNECTION);
        for (String h : HOP_BY_HOP) {
            out.remove(h);
        }
        return out;
    }

    /** Connection 头里可以点名「本连接结束就删」的头，这些也要摘掉。 */
    private static void removeHopByHopNamed(HttpHeaders in, HttpHeaders out) {
        for (String connectionValue : in.getConnection()) {
            for (String token : connectionValue.split(",")) {
                String name = token.trim();
                if (!name.isEmpty()) {
                    out.remove(name);
                }
            }
        }
    }

    /**
     * 按顺序号执行一组动作（调用方传入时已排好序）：
     * 补头用 set——同名时网关配置的值覆盖调用方/上游自带的值，不许调用方自己做主；
     * 删头用 remove——删干净。
     */
    public static void apply(HttpHeaders headers, List<HeaderAction> actions) {
        for (HeaderAction a : actions) {
            if (a.add()) {
                headers.set(a.name(), a.value());
            } else {
                headers.remove(a.name());
            }
        }
    }

    /** 供日志/调试用：把一组头名按小写列出来。 */
    public static List<String> lowerNames(HttpHeaders headers) {
        List<String> names = new ArrayList<>(headers.size());
        headers.keySet().forEach(n -> names.add(n.toLowerCase(Locale.ROOT)));
        return names;
    }
}
