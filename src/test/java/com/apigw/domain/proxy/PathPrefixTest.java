package com.apigw.domain.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径前缀的边界语义：以斜杠结尾与不以斜杠结尾必须分得清。
 */
class PathPrefixTest {

    @Test
    void trailingSlash_requiresPathInsidePrefix() {
        PathPrefix p = PathPrefix.compile("/order/");
        assertTrue(p.matches("/order/abc"), "落在目录里面要命中");
        assertTrue(p.matches("/order/"), "目录本身（带斜杠）要命中");
        assertTrue(p.matches("/order/a/b/c"));
        assertFalse(p.matches("/order"), "规则以斜杠结尾时，/order 不在它里面，不能命中");
        assertFalse(p.matches("/orderabc"));
        assertFalse(p.matches("/other"));
    }

    @Test
    void noTrailingSlash_matchesExactOrSegmentBoundary() {
        PathPrefix p = PathPrefix.compile("/order");
        assertTrue(p.matches("/order"), "规则不以斜杠结尾时，/order 本身要命中");
        assertTrue(p.matches("/order/abc"), "路径段边界后的子路径要命中");
        assertFalse(p.matches("/orderabc"), "只是字符串前缀相同、越过段边界的不能命中");
        assertFalse(p.matches("/other"), "题目点名：/order 规则对 /other 绝不命中");
        assertFalse(p.matches("/orders"));
    }

    @Test
    void root_matchesEverything() {
        PathPrefix p = PathPrefix.compile("/");
        assertTrue(p.matches("/order/abc"));
        assertTrue(p.matches("/anything"));
        assertTrue(p.matches("/"));
    }

    @Test
    void pathIsCaseSensitive_byConventionalUrlSemantics() {
        PathPrefix p = PathPrefix.compile("/order");
        assertFalse(p.matches("/Order"));
        assertFalse(p.matches("/ORDER/abc"));
        assertTrue(p.matches("/order"));
    }

    @Test
    void missingLeadingSlash_isNormalized() {
        assertTrue(PathPrefix.compile("order/").matches("/order/abc"));
        assertFalse(PathPrefix.compile("order/").matches("/order"));
    }

    @Test
    void blank_isRejected() {
        assertFalse(PathPrefix.compile("/x").matches(""));
    }
}
