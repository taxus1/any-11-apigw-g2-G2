package com.apigw.application.proxy;

/**
 * 路由配置发生了增/改/删的领域事件。管理接口写库成功后发出，
 * 转发链路据此把内存里的路由表重新拉一遍，做到新配置不用重启就生效。
 */
public record RouteChangedEvent(String routeNo, String operation) {
}
