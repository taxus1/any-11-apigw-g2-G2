package com.apigw.domain.proxy;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpstreamUrlsTest {

    @Test
    void joinsBasePathWithRequestPath() {
        URI uri = UpstreamUrls.build("http://svc:8080", "/order/abc", null);
        assertEquals("http", uri.getScheme());
        assertEquals("svc", uri.getHost());
        assertEquals(8080, uri.getPort());
        assertEquals("/order/abc", uri.getRawPath());
    }

    @Test
    void keepsBasePrefix_withoutDoubledSlash() {
        URI uri = UpstreamUrls.build("http://svc/base/", "/order/abc", null);
        assertEquals("/base/order/abc", uri.getRawPath());
    }

    @Test
    void carriesRawQuery_andMergesBaseQuery() {
        assertEquals("a=1&b=2",
                UpstreamUrls.build("http://svc?a=1", "/x", "b=2").getRawQuery());
        assertEquals("a=1",
                UpstreamUrls.build("http://svc?a=1", "/x", null).getRawQuery());
        assertEquals("b=2",
                UpstreamUrls.build("http://svc", "/x", "b=2").getRawQuery());
    }

    @Test
    void preservesPercentEncoding() {
        URI uri = UpstreamUrls.build("http://svc", "/order/%E4%B8%AD%E6%96%87", "q=a%20b");
        assertEquals("/order/%E4%B8%AD%E6%96%87", uri.getRawPath());
        assertEquals("q=a%20b", uri.getRawQuery());
    }
}
