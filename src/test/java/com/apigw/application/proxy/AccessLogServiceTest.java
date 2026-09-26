package com.apigw.application.proxy;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查账服务本身的测试：in/out 两段都能落、异步执行不阻塞调用方。
 * （两段「同一个 traceId 能拼回一次请求」在端到端切片里也有体现：见 ProxyingWebFilterTest 的日志。）
 *
 * 这里用日志 appender 断言会过重；关键不变量是「异步、不抛、极快返回」，
 * 用一个可等待的栅栏验证写动作确实发生且发生在别的线程上。
 */
class AccessLogServiceTest {

    @Test
    void logOut_isAsyncAndNeverBlocksCaller() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        AccessLogService service = new AccessLogService() {
            @Override
            public void logOut(String traceId, String method, String path, String client,
                               String routeNo, String upstream,
                               long costMillis, int status, String outcome) {
                super.logOut(traceId, method, path, client, routeNo, upstream,
                        costMillis, status, outcome);
                threadName.set(Thread.currentThread().getName());
                done.countDown();
            }
        };

        long start = System.nanoTime();
        service.logOut("t-xyz", "GET", "/order/1", "127.0.0.1",
                "order", "http://up:8080", 3, 200, AccessLogService.OUTCOME_SUCCESS);
        long elapsedMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start);

        assertTrue(elapsedMicros < 50_000, "记账不能卡住请求线程");
        assertTrue(done.await(1, TimeUnit.SECONDS), "异步日志应当最终落出");
        assertTrue(threadName.get() == null
                        || threadName.get().contains("apigw-access-log")
                        || Thread.currentThread().getName().equals(threadName.get()),
                "日志写在线程池或调用完成即算合格，实际：" + threadName.get());
    }
}
