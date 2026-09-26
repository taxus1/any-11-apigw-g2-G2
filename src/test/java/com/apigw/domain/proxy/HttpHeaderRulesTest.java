package com.apigw.domain.proxy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 头部动作规则：补头是覆盖、删头是删除、按顺序执行；请求与响应两组规则各管一段；
 * 以及转发时 hop-by-hop、Content-Length/Transfer-Encoding 的处理。
 */
class HttpHeaderRulesTest {

    private HttpHeaders headers(String... kv) {
        HttpHeaders h = new HttpHeaders();
        for (int i = 0; i < kv.length; i += 2) {
            h.add(kv[i], kv[i + 1]);
        }
        return h;
    }

    @Test
    void addHeader_overwritesCallerValue_callerCannotWin() {
        HttpHeaders in = headers("X-K", "caller-value");
        HttpHeaders out = HttpHeaderRules.prepareUpstream(
                in, List.of(HeaderAction.add("X-K", "gw-value", 1)), "t1");

        assertEquals(List.of("gw-value"), out.get("X-K"),
                "调用方自带同名头时，必须被网关配置覆盖");
    }

    @Test
    void removeHeader_isActuallyGone() {
        HttpHeaders in = headers("X-Internal", "secret", "X-K", "keep");
        HttpHeaders out = HttpHeaderRules.prepareUpstream(
                in, List.of(HeaderAction.remove("X-Internal", 1)), "t1");

        assertNull(out.getFirst("X-Internal"), "删头就是删掉，上游不能再收到");
        assertEquals("keep", out.getFirst("X-K"));
    }

    @Test
    void actionsRunInSortOrder_orderChangesResult() {
        HttpHeaders in = new HttpHeaders();
        // 先补后删：X 最终不存在
        HttpHeaders out1 = HttpHeaderRules.prepareUpstream(in,
                List.of(HeaderAction.add("X", "v", 1), HeaderAction.remove("X", 2)), "t");
        assertFalse(out1.containsKey("X"));

        // 先删后补：X 最终在（顺序号反过来，结果相反）
        HttpHeaders out2 = HttpHeaderRules.prepareUpstream(in,
                List.of(HeaderAction.remove("X", 1), HeaderAction.add("X", "v", 2)), "t");
        assertEquals("v", out2.getFirst("X"));
    }

    @Test
    void requestSide_stripsHopByHopAndHostAndTe_butKeepsContentLength() {
        HttpHeaders in = headers(
                "Host", "caller.example",
                "Connection", "close, X-Bespoke",
                "Keep-Alive", "timeout=5",
                "X-Bespoke", "drop-me",
                "Transfer-Encoding", "chunked",
                "Content-Length", "12",
                "X-K", "keep");

        HttpHeaders out = HttpHeaderRules.prepareUpstream(in, List.of(), "t1");
        assertNull(out.getFirst("Host"), "Host 由上游 URL 决定");
        assertNull(out.getFirst("Connection"));
        assertNull(out.getFirst("Keep-Alive"));
        assertNull(out.getFirst("X-Bespoke"), "Connection 点名的头也要摘");
        assertNull(out.getFirst("Transfer-Encoding"), "是否分块由客户端按身体重定");
        assertEquals("12", out.getFirst("Content-Length"), "请求体长度与实际一致，保留");
        assertEquals("keep", out.getFirst("X-K"));
        assertEquals("t1", out.getFirst(HttpHeaderRules.TRACE_ID_HEADER));
    }

    @Test
    void responseSide_neverCopiesContentLengthOrTransferEncoding() {
        HttpHeaders upstream = headers(
                "Content-Length", "999",
                "Transfer-Encoding", "chunked",
                "Connection", "keep-alive",
                "X-Debug", "dbg",
                "Content-Type", "application/json");

        HttpHeaders out = HttpHeaderRules.prepareClient(upstream,
                List.of(HeaderAction.remove("X-Debug", 1),
                        HeaderAction.add("X-Trace", "t-1", 2)));

        assertNull(out.getFirst("Content-Length"),
                "上游给的长度不能照抄，交给框架按真实字节重算");
        assertNull(out.getFirst("Transfer-Encoding"));
        assertNull(out.getFirst("Connection"));
        assertNull(out.getFirst("X-Debug"), "响应删头要落到回给调用方的报文上");
        assertEquals("t-1", out.getFirst("X-Trace"), "响应补头要落到回给调用方的报文上");
        assertEquals("application/json", out.getFirst("Content-Type"));
    }

    @Test
    void requestAndResponseGroups_areIndependent() {
        // 方向分组在 RouteCatalog.compile 里完成；这里验证两组各用各的报文
        HttpHeaders respIn = headers("X-Req-Only", "v");
        HttpHeaders respOut = HttpHeaderRules.prepareClient(respIn, List.of());
        assertEquals("v", respOut.getFirst("X-Req-Only"),
                "请求侧的头不参与响应处理，原样转发");

        HttpHeaders reqIn = headers("X-K", "caller");
        HttpHeaders reqOut = HttpHeaderRules.prepareUpstream(reqIn,
                List.of(HeaderAction.add("X-K", "gw", 1)), "t");
        assertEquals(List.of("gw"), reqOut.get("X-K"));
    }

    @Test
    void queryAndHeaderConditions_matchExactly() {
        LinkedMultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("from", "cart");
        HttpHeaders h = headers("X-Caller", "web");
        RequestSnapshot req = new RequestSnapshot("GET", "/order/abc", query, h);

        assertTrue(new CompiledRoute.Condition("HEADER", null, "X-Caller", "web").matches(req));
        assertFalse(new CompiledRoute.Condition("HEADER", null, "X-Caller", "app").matches(req));
        assertTrue(new CompiledRoute.Condition("QUERY", null, "from", "cart").matches(req));
        assertFalse(new CompiledRoute.Condition("QUERY", null, "from", "home").matches(req));
        assertTrue(new CompiledRoute.Condition("METHOD", null, null, "GET").matches(req));
        assertTrue(new CompiledRoute.Condition("METHOD", null, null, "get").matches(req),
                "方法按常规语义不区分大小写");
    }
}
