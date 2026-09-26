package com.apigw.application.proxy;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 路由表刷新器：三种时机把 Redis 里的最新配置拉进内存。
 *
 * 1. 启动后先拉一次；
 * 2. 管理接口发 {@link RouteChangedEvent} 后延迟一小会儿（150ms）拉一次——
 *    同一条路由连续改几次也只多一次全量拉取，成本可忽略，但「新配一条立刻能走通」不用重启；
 * 3. 定时轮询兜底：多个网关实例时，实例 A 改的配置，实例 B 不靠消息也能在几秒内跟上。
 *
 * 任何一次刷新失败都只记日志并保留旧表，等下一轮再试，绝不把在跑的表清空。
 */
@Slf4j
@Component
public class RouteCatalogRefresher {

    private final RouteCatalog catalog;

    /** 防止启动/事件/定时叠在一起同时拉取。 */
    private final AtomicBoolean inflight = new AtomicBoolean(false);

    public RouteCatalogRefresher(RouteCatalog catalog) {
        this.catalog = catalog;
    }

    @PostConstruct
    void init() {
        refresh("startup");
    }

    @EventListener
    public void onRouteChanged(RouteChangedEvent event) {
        // 给连续的批量改动一个自然合并窗口，避免一刷接一刷
        Schedulers.parallel().schedule(
                () -> refresh("event:" + event.operation()), 150, TimeUnit.MILLISECONDS);
    }

    /** 定时兜底，默认 3s 一次；多实例改配置也能在秒级跟上。 */
    @Scheduled(fixedDelayString = "${apigw.proxy.route-refresh-ms:3000}", initialDelayString = "${apigw.proxy.route-refresh-ms:3000}")
    public void scheduled() {
        refresh("scheduled");
    }

    /**
     * 触发一次刷新。失败不抛、不清表；block 发生在非请求线程（启动钩子/定时器/事件延迟线程）上，
     * 与请求链路互不影响。
     */
    private void refresh(String reason) {
        if (!inflight.compareAndSet(false, true)) {
            return;
        }
        try {
            catalog.reload().block(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("路由表刷新失败（{}），继续沿用刷新前的路由表：{}", reason, e.getMessage());
        } finally {
            inflight.set(false);
        }
    }
}
