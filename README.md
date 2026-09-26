# apigw · 微服务网关

Spring Cloud Gateway（WebFlux 响应式）+ Redis 动态路由配置。JDK 17 / Spring Boot 3.2.5 / Spring Cloud 2023.0.1。

同一个应用里跑两件事：

1. **转发链路**：请求进来 → 按配置的匹配条件找到路由 → 按配置的转发动作处理请求头 → 打到上游 → 响应回来按顺序号处理响应头 → 交还调用方。任何一步出错，调用方拿到的是网关自己说得清的答复，不是内部堆栈或上游原始错误页。
2. **管理接口**：`/api/gateway/routes`，维护路由及其匹配条件、转发动作。配置改完存 Redis，转发链路秒级热刷新，不用重启。

## 起环境

```bash
docker compose up -d                # 起 Redis（路由配置存在这里）
mvn spring-boot:run                 # 网关，8080
bash tools/start-echo-upstream.sh   # 本地回显上游，8091（另开一个终端）
```

## 转发链路怎么走

入口是 `com.apigw.interfaces.web.ProxyingWebFilter`（最高优先级的 WebFilter），`/api/gateway/**` 管理接口直接放行，其余请求按下面走：

1. 分配/沿用 `X-Trace-Id`，记「进来」一段账；
2. 用内存路由表（`RouteCatalog`）按条件找路由；
3. 命中后，该路由的**请求类动作**按顺序号作用到发给上游的报文，然后打到上游；
4. 上游响应回来，**响应类动作**按顺序号作用到回给调用方的报文，再交还调用方；
5. 记「回去」一段账（命中路由、上游、耗时、状态码）。

### 匹配规则

- 四类条件（路径前缀 / 方法 / 请求头 / 查询参数）在一条路由内是「且」：全部命中才算这条路由命中。
- **路径前缀踩准段边界**：
  - 规则 `/order/`（斜杠结尾）：`/order/abc`、`/order/` 命中；`/order` 不命中；
  - 规则 `/order`（无斜杠）：`/order`、`/order/abc` 命中；`/orderabc`、`/other` 绝不命中；
  - 规则 `/`：命中一切。
- 大小写按常规 URL 语义：路径区分大小写，方法不区分；头名不区分、头值精确相等；查询参数名区分大小写、值精确相等。
- **多条路由都命中时的稳定定序**：最长路径前缀 → 条件数多者 → 路由编号字典序。同样的请求永远走同一条。
- 一张都没命中：回 **404** + `X-Gateway-Error: GATEWAY_NO_ROUTE` + JSON 错误体，调用方一眼看出是「网关没找着路」，不是后端服务出错。

### 动作规则

- 补头是**覆盖**：调用方自带同名头时，以网关配置的值为准；删头就是删干净，上游收不到。
- 同一批动作严格按配置的顺序号执行，顺序不同结果不同（先补后删 ≠ 先删后补）。
- 请求类动作只作用在发给上游的报文，响应类动作只作用在回给调用方的报文，两个方向互不串门。

### 出错时调用方看到什么

| 情形 | HTTP | X-Gateway-Error | 错误体 |
| --- | --- | --- | --- |
| 没匹配到路由 | 404 | `GATEWAY_NO_ROUTE` | 网关 JSON |
| 上游连不上 | 502 | `GATEWAY_UPSTREAM_UNAVAILABLE` | 网关 JSON |
| 上游超时（默认 10s 无应答） | 504 | `GATEWAY_UPSTREAM_TIMEOUT` | 网关 JSON |
| 网关自身意外故障 | 500 | `GATEWAY_ERROR` | 网关 JSON（细节只进服务端日志） |
| 上游自己回的 4xx/5xx | 原样 | 无标记 | 原样透传（这是上游业务的答复，不是网关故障） |

错误体形如 `{"code":"GATEWAY_NO_ROUTE","message":"...","traceId":"..."}`，并带 `X-Trace-Id` 头，可拿它去访问日志里对账。转发超时/建连超时可用 `apigw.proxy.connect-timeout` / `apigw.proxy.response-timeout` 调整。

### 报文头怎么搬

- hop-by-hop 头（`Connection`、`Keep-Alive`、`TE`、`Trailer`、`Upgrade`、`Proxy-*`，以及 `Connection` 点名的头）转发时一律摘掉；
- `Host` 由目标上游地址重新给出；
- 上游响应的 `Content-Length` / `Transfer-Encoding` **不照抄**——身体由网关重新写出，交给 Netty 按真实字节走分块，绝不出现「头里说 100、实际 80」；
- 不跟随上游 3xx 重定向，原样交还调用方。

### 回头查账

`com.apigw.access` 日志，每笔转发两段，同一个 `traceId` 可拼回一次请求：

```
phase=in  traceId=... method=GET path=/order/abc client=127.0.0.1
phase=out traceId=... method=GET path=/order/abc client=... routeNo=order-route upstream=http://order-svc:8080 status=200 costMs=12 outcome=SUCCESS
```

`outcome` 分类：`SUCCESS`（拿到上游应答，含上游 4xx/5xx）、`NO_ROUTE`、`UPSTREAM_UNAVAILABLE`、`UPSTREAM_TIMEOUT`、`GATEWAY_ERROR`。写日志走独立守护线程，绝不卡住请求。

### 热刷新

管理接口写库成功后发 `RouteChangedEvent`，转发链路 150ms 后重新拉表；另有定时轮询（默认 3s，`apigw.proxy.route-refresh-ms`）兜底多实例。刷新失败沿用旧表，绝不清空在跑的路由。新配/改/删一条路由，秒级生效，不用重启。

## 管理接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/gateway/routes` | 新建路由（连同条件与动作一起落库） |
| PUT | `/api/gateway/routes/{routeNo}` | 修改路由（整树替换，必须带 `version`） |
| GET | `/api/gateway/routes/{routeNo}` | 路由详情（含全部子项，按顺序号排好） |
| GET | `/api/gateway/routes?pageNum=&pageSize=&keyword=` | 分页列表（每条带条件/动作计数） |
| DELETE | `/api/gateway/routes/{routeNo}?expectVersion=` | 删除路由（整树清掉） |

所有接口返回统一结构 `{ code, msg, data }`：

- `code=0` 成功；
- 通用业务失败 `code=1`；
- 路由不存在 `code=404`（删除不存在的路由不算成功）；
- 并发冲突 `code=409`（你手上的版本旧了）。

### 请求体形状

```json
{
  "routeNo": "order-route",
  "name": "订单服务路由",
  "upstream": "http://order-svc:8080",
  "enabled": 1,
  "remark": "给前端下单用",
  "version": 0,
  "conditions": [
    { "type": "PATH_PREFIX", "value": "/order/", "sortNo": 1 },
    { "type": "METHOD", "value": "GET", "sortNo": 2 },
    { "type": "HEADER", "name": "X-Caller", "value": "web", "sortNo": 3 },
    { "type": "QUERY", "name": "from", "value": "cart", "sortNo": 4 }
  ],
  "actions": [
    { "type": "REQ_ADD_HEADER", "name": "X-Gw", "value": "1", "sortNo": 1 },
    { "type": "REQ_REMOVE_HEADER", "name": "X-Internal", "sortNo": 2 },
    { "type": "RESP_ADD_HEADER", "name": "X-Trace", "value": "t-1", "sortNo": 3 },
    { "type": "RESP_REMOVE_HEADER", "name": "X-Debug", "sortNo": 4 }
  ]
}
```

- 路由编号：业务唯一，建后**不可改**（PUT 的 body 里编号与路径不一致会被拦）；停用的路由也占号，只有删除才释放编号。
- 匹配条件只认 `PATH_PREFIX` / `METHOD` / `HEADER` / `QUERY`；路径、方法两类不用填 `name`。
- 转发动作只认 `REQ_ADD_HEADER` / `REQ_REMOVE_HEADER` / `RESP_ADD_HEADER` / `RESP_REMOVE_HEADER`；删头不用填 `value`。
- 顺序号每组各自从 1 开始，必须**连续、不重**。撞号会报「匹配条件第 a 条与第 b 条的顺序号撞了，都是 n」；跳号会报缺了第几。
- 上游地址必须是合法的 `http://` / `https://` URL（协议、主机、端口都像样），空串和乱码不收。
- 修改时把条件/动作整批重排提交即可，服务端按新一批顺序号整树替换。

### 分页返回

```json
{
  "code": 0,
  "data": {
    "content": [ { "routeNo": "...", "conditionCount": 2, "actionCount": 4, "...": "..." } ],
    "total": 37,
    "pageNum": 2,
    "pageSize": 20,
    "totalPages": 2
  }
}
```

- `pageNum` 从 1 开始；`pageSize` 上限 200（传 99999 也只按 200 算），防止一次拖全量。
- `keyword` 在编号和名称上做忽略大小写的模糊匹配。
- 列表每行直接带 `conditionCount` / `actionCount`，前端不用逐条再查。

## 配置怎么存

```
Redis key   apigw:routes          类型 Hash
            field = routeNo
            value = 该路由连同全部条件、动作的一整份 JSON
```

**为什么「一条路由 + 它的全部子项」塞在一个 field 里**：保存是一次 `HSET`、删除是一次 `HDEL`，Redis 单命令原子，所以「全落库或全不落」不需要手工回滚，也不可能读出主记录在、子项不在的残缺路由；删除时一次 `HDEL` 整树清掉，没有无主子记录可留。

## 并发怎么控

两层，都在 Redis 上：

1. **建路由占号用 `HSETNX`**：「查编号是否存在」和「写入」合成一个原子动作。两个人同时建同一个编号，只有一个成功，另一个收「编号已被占用（停用的路由也占号）」。
2. **改/删同一条用「短租约锁 + version 乐观锁」**：
   - 锁 key `apigw:lock:route:{routeNo}`，`SET NX` 带 5 秒 TTL，值是唯一 token，释放走 Lua 比对 token 后删除（不会误删别人的锁）；它把「读当前版本 → 写回」串成临界区。
   - 每条路由带 `version`：**修改必须显式带上读取时拿到的版本**（首版传 0），服务端比对一致才写、然后 version+1；不一致返回 `code=409`「你这份配置已经旧了（当前版本 n，你手上是 m），请重新拉取后再提交」。删除带 `expectVersion` 时有同样保护。
   - 不允许不带版本就改，否则等于把乐观锁绕过去、静默覆盖。

## 测试

```bash
docker compose up -d     # 提供真实 Redis
mvn test
```

- `GatewayRouteTest`：聚合不变量（编号不可改、上游地址、顺序号撞/跳并报位置、类型白名单、必填项），无需 Redis。
- `GatewayRouteControllerWebTest`：HTTP 切片（真实 Controller + AppService + 聚合，mock 掉 Redis），覆盖统一返回、报错文案、分页数字与子项计数。
- `PathPrefixTest` / `HttpHeaderRulesTest` / `UpstreamUrlsTest`：路径前缀边界、头动作（覆盖/删除/顺序）、hop-by-hop 与长度头处理、上游 URL 拼装，纯单测。
- `RouteCatalogTest`：路由表编译、热更新、多路由稳定定序、动作按方向分组排序（store 用 mock）。
- `ProxyingWebFilterTest`：转发链路端到端切片（真实 WebFilter + 真实 WebClient + 裸 HTTP 上游，不依赖 Redis）：边界匹配、动作落到真实报文、404/502/504 分类、上游 500 透传、长度头不照抄、热刷新。
- `RouteStoreTest` / `GatewayRouteControllerIT` / `ForwardingChainIT`：连真实 Redis，覆盖 HSETNX 原子占号、并发建同号、乐观锁 409、整树替换与级联删除、管理接口写库后事件热刷新全链路。本机探测不到 `localhost:6379` 时自动跳过（可用 `-Dredis.host/-Dredis.port` 指向别处）。

## 已知边界（留给后续题目）

- 管理接口未鉴权；接入鉴权与身份透传是后续 F2 的题。
- 转发链路不处理熔断/限流/灰度，属后续题目。
