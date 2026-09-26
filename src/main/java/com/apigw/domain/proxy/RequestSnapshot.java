package com.apigw.domain.proxy;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

/**
 * 匹配时需要看到的请求快照：方法、原始路径（不含查询串）、查询参数、请求头。
 *
 * 只取匹配用得到的字段，不让匹配逻辑碰到请求体与任何可变对象。
 */
public record RequestSnapshot(String method,
                              String rawPath,
                              MultiValueMap<String, String> queryParams,
                              HttpHeaders headers) {
}
