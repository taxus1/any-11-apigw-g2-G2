package com.apigw.domain.proxy;

/**
 * 一条「补头 / 删头」动作的编译结果，请求方向与响应方向各成一组，互不串门。
 *
 * 补头是「覆盖」语义：调用方自带同名头时，以网关配置的值为准；
 * 删头就是删掉，转发的报文里不许再出现。同组动作按 {@link #sortNo()} 顺序执行，
 * 顺序不同结果可能不同（先补 X 再删 X 与先删 X 再补 X 相反）。
 */
public record HeaderAction(boolean add, String name, String value, int sortNo) {

    public static HeaderAction add(String name, String value, int sortNo) {
        return new HeaderAction(true, name, value, sortNo);
    }

    public static HeaderAction remove(String name, int sortNo) {
        return new HeaderAction(false, name, null, sortNo);
    }
}
