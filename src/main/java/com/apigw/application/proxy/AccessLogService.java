package com.apigw.application.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 转发查账（访问日志）：每笔转发记「进来」「回去」两段，用同一个 traceId 串起来。
 *
 * 两段各自成行（phase=in / phase=out），拿到日志后按 traceId 就能拼回同一次请求，
 * 不会串到别人身上；out 段带上命中路由、上游、耗时与最终状态。
 *
 * 写日志不能卡住请求本身：这里用一条独立的守护线程异步落盘，IO 抖动也不占着 Netty 线程。
 */
@Slf4j
@Service
public class AccessLogService {

    /** 结果分类，供统计/告警按类分流。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_NO_ROUTE = "NO_ROUTE";
    public static final String OUTCOME_UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    public static final String OUTCOME_UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT";
    public static final String OUTCOME_GATEWAY_ERROR = "GATEWAY_ERROR";

    private static final org.slf4j.Logger ACCESS =
            org.slf4j.LoggerFactory.getLogger("com.apigw.access");

    private final ExecutorService writer;

    public AccessLogService() {
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "apigw-access-log-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.writer = Executors.newSingleThreadExecutor(tf);
    }

    /** 请求进来的一段。 */
    public void logIn(String traceId, String method, String path, String client) {
        submit(() -> ACCESS.info("phase=in traceId={} method={} path={} client={}",
                traceId, method, path, client));
    }

    /**
     * 响应回去的一段。
     *
     * @param costMillis 这次转发端到端耗时（毫秒）
     * @param routeNo    命中的路由编号；没命中为 null
     * @param upstream   上游地址；没命中为 null
     * @param status     调用方最终看到的 HTTP 状态码
     * @param outcome    结果分类，见本类常量
     */
    public void logOut(String traceId, String method, String path, String client,
                       String routeNo, String upstream,
                       long costMillis, int status, String outcome) {
        submit(() -> ACCESS.info(
                "phase=out traceId={} method={} path={} client={} routeNo={} upstream={} status={} costMs={} outcome={}",
                traceId, method, path, client,
                routeNo == null ? "-" : routeNo,
                upstream == null ? "-" : upstream,
                status, costMillis, outcome));
    }

    /** 异步写：绝不让日志系统的抖动反过来卡住转发。 */
    private void submit(Runnable task) {
        try {
            writer.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.debug("写访问日志失败，已忽略：{}", e.getMessage());
                }
            });
        } catch (Exception e) {
            // 线程池关停等极端情况：查账可以丢，请求不能因此失败
            log.debug("访问日志线程池不可用，已忽略：{}", e.getMessage());
        }
    }
}
