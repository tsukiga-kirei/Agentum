# OA Basic 单点登录对接示例

本文给出 OA 等已完成自身用户认证的业务系统接入 Agentum Basic 单点登录的完整示例。目标是让用户在 OA 点击菜单后直接进入 Agentum，同时确保共享密码、Access Token 和 Refresh Token 不进入浏览器 URL 或 OA 前端代码。

通用企业 SSO 的边界、OIDC 方案和数据模型见 [企业 SSO 对接说明](./sso-integration.md)。

## 1. 适用场景

Basic 方案适用于以下情况：

- 用户已经登录 OA，OA 服务端能可靠取得当前用户账号。
- OA 暂时不能提供标准 OAuth2 / OIDC Provider。
- Agentum 与 OA 之间可以维护一份租户级共享密码。
- OA 服务端能够调用 Agentum API，并把 Agentum 返回的地址重定向给用户浏览器。

如果 OA 已经具备标准 OIDC 能力，应优先使用 OIDC。Basic 方案是受信任业务系统之间的服务端单点入口，不是让用户输入 OA 密码登录 Agentum。

## 2. 登录流程

```text
用户已经登录 OA
  -> 用户点击 Agentum 菜单
  -> OA 服务端取得当前 loginid
  -> OA 服务端携带 Basic 凭据调用 Agentum basic-redirection
  -> Agentum 校验共享密码、来源白名单、租户、用户和入口角色
  -> Agentum 在 Redis 写入 60 秒、仅可消费一次的登录交接码
  -> Agentum 返回 302 Location
  -> OA 不跟随 302，只读取 Location
  -> OA 使用 sendRedirect 把用户浏览器跳到 Location
  -> 浏览器访问 Agentum basic-consume
  -> Agentum 再次校验用户和角色，写入 Refresh Cookie
  -> 登录桥接页保存短期 Access Token，进入 Agentum 工作台
```

这里最重要的边界是：OA 的服务端 HTTP 客户端只负责换取 `Location`，不能自动跟随 302。最终必须由用户浏览器访问 Agentum 域名，Agentum 才能为浏览器建立自己的登录会话。

## 3. Agentum 侧准备

### 3.1 启用租户 Basic 企业认证

系统管理员在目标租户的企业认证配置中填写：

| 配置 | 说明 |
| --- | --- |
| 启用状态 | 开启企业认证 |
| 认证方式 | `Basic` |
| 共享密码 | OA 服务端调用时使用的租户级共享密码 |
| 允许 IP | OA 服务端或网关访问 Agentum 时呈现的来源 IP；留空表示不限制 |
| 允许域名 | OA 请求携带的 `Origin` / `Referer` 主机；留空表示不限制 |

管理页面会按当前 Agentum API 地址展示可复制的“OA 服务端换址接口”，并按当前租户编码展示可复制的 `tenantCode/<OA loginid>` 用户名格式。共享密码只在首次输入或生成时显示；保存后后端仅返回“已配置”状态，不返回密码明文或密文。如需更换，应生成或输入新密码，先复制到 OA 服务端，再保存 Agentum 配置。

首次联调可以暂时不配置 IP 和域名白名单。链路验证通过后，生产环境至少配置允许 IP，并使用 HTTPS。

### 3.2 准备本地用户和入口角色

OA 传入的 `loginid` 必须能匹配 Agentum 本地 `users.username`。Agentum 不会因为 Basic 登录自动创建用户。

用户还必须满足：

- 账号状态为启用。
- 属于 Basic username 中 `tenantCode` 对应的启用租户。
- 拥有请求 `portal` 对应的入口角色。

当前支持的 `portal`：

- `business`：业务用户入口。
- `tenant_admin`：租户管理入口。

系统管理员入口不使用租户级 Basic 单点登录。

### 3.3 运行依赖

一次性登录交接码存放在 Redis，因此 Agentum API、PostgreSQL 和 Redis 必须可用。本地可执行：

```bash
make dev-infra
```

## 4. Basic 换址接口

OA 服务端调用：

```http
GET /api/auth/sso/basic-redirection?portal=business
Authorization: Basic base64(tenantCode/username:sharedPassword)
Origin: https://oa.example.com
```

Basic username 必须使用：

```text
tenantCode/username
```

例如租户编码为 `example`、用户为 `zhangsan` 时，参与 Base64 编码的原始凭据为：

```text
example/zhangsan:这里是共享密码
```

认证成功响应：

```http
HTTP/1.1 302 Found
Cache-Control: no-store
Location: https://agentum.example.com/api/auth/sso/basic-consume?code=一次性交接码
```

OA 不能把 Basic username、共享密码或 Agentum Token 拼进浏览器跳转 URL，只能把响应中的 `Location` 原样交给浏览器。

## 5. OA Java 对接示例

以下示例适用于 Java 8，并只依赖 JDK 的 `HttpURLConnection`。实际 OA 项目可以把变量放入安全配置；如果使用类内静态变量，生产共享密码不得提交到公开仓库。

```java
private static String AGENTUM_BASIC_REDIRECTION_URL =
    "https://agentum.example.com/api/auth/sso/basic-redirection";
private static String AGENTUM_TENANT_CODE = "example";
private static String AGENTUM_SHARED_PASSWORD = "请填写租户 Basic 共享密码";
private static String AGENTUM_PORTAL = "business";
private static String AGENTUM_SOURCE_ORIGIN = "https://oa.example.com";
private static int AGENTUM_CONNECT_TIMEOUT_MS = 3000;
private static int AGENTUM_READ_TIMEOUT_MS = 5000;
```

服务端换取浏览器地址：

```java
private static String requestAgentumBrowserLoginUrl(String username) throws IOException {
    String separator = AGENTUM_BASIC_REDIRECTION_URL.contains("?") ? "&" : "?";
    String requestUrl = AGENTUM_BASIC_REDIRECTION_URL
        + separator
        + "portal="
        + URLEncoder.encode(AGENTUM_PORTAL, "UTF-8");

    HttpURLConnection connection = null;
    try {
        connection = (HttpURLConnection) new URL(requestUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(AGENTUM_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(AGENTUM_READ_TIMEOUT_MS);

        // 必须禁止自动跟随，否则一次性交接码会被 OA 服务端消费，浏览器再次打开时已经失效。
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "text/html");
        connection.setRequestProperty(
            "Authorization",
            basicAuthorization(AGENTUM_TENANT_CODE, username, AGENTUM_SHARED_PASSWORD)
        );
        if (AGENTUM_SOURCE_ORIGIN != null && !AGENTUM_SOURCE_ORIGIN.trim().isEmpty()) {
            connection.setRequestProperty("Origin", AGENTUM_SOURCE_ORIGIN.trim());
        }

        int status = connection.getResponseCode();
        String location = connection.getHeaderField("Location");
        if (status == HttpURLConnection.HTTP_MOVED_TEMP
            && location != null
            && !location.trim().isEmpty()) {
            return location;
        }
        throw new IOException("Agentum Basic 换址失败，HTTP 状态：" + status);
    } finally {
        if (connection != null) {
            connection.disconnect();
        }
    }
}

private static String basicAuthorization(
    String tenantCode,
    String username,
    String sharedPassword
) {
    String credential = tenantCode.trim()
        + "/"
        + username.trim()
        + ":"
        + sharedPassword;
    return "Basic " + Base64.getEncoder().encodeToString(
        credential.getBytes(StandardCharsets.UTF_8)
    );
}
```

OA JAX-RS 入口示例：

```java
@GET
@Path("getToken")
@Produces(MediaType.TEXT_HTML)
public void getToken(
    @Context HttpServletRequest request,
    @Context HttpServletResponse response
) throws IOException {
    User user = CommonUtil.getUserByRequest(request, response);
    String loginid = user == null ? "" : user.getLoginid();
    if (loginid == null || loginid.trim().isEmpty()) {
        response.sendError(
            HttpServletResponse.SC_UNAUTHORIZED,
            "无法识别当前 OA 用户，请重新登录 OA"
        );
        return;
    }

    try {
        String location = requestAgentumBrowserLoginUrl(loginid.trim());
        response.sendRedirect(location);
    } catch (IOException exception) {
        // 日志只记录状态和错误类型，禁止输出 Authorization、共享密码、Location 中的一次性交接码或 Token。
        response.sendError(
            HttpServletResponse.SC_BAD_GATEWAY,
            "暂时无法进入 Agentum，请联系管理员"
        );
    }
}
```

## 6. 独立 main 方法测试

正式接入 OA 菜单前，可以先写一个不依赖 OA 运行时的 `main` 方法：使用固定测试用户名调用 `requestAgentumBrowserLoginUrl`，把返回地址打印到控制台。

```java
public static void main(String[] args) {
    try {
        String location = requestAgentumBrowserLoginUrl("测试用户名");
        System.out.println("Agentum 一次性登录地址：");
        System.out.println(location);
        System.out.println("请在 60 秒内复制到浏览器打开，该地址只能使用一次。");
    } catch (Exception exception) {
        System.err.println("Agentum Basic 登录测试失败：" + exception.getMessage());
    }
}
```

测试程序必须保留 `setInstanceFollowRedirects(false)`。如果测试程序自动跟随 302，一次性交接码会被测试程序先消费，随后复制到浏览器时会提示地址无效。

## 7. 本地联调

本地前端使用 Vite `5173` 端口，API 使用 `8080` 端口。为了让登录桥接页和 Agentum 前端处于同一浏览器 Origin，启动 API 时把 SSO 对外地址配置为前端地址：

```bash
./gradlew :apps:api:bootRun \
  --args='--agentum.auth.sso-api-base-url=http://localhost:5173 --agentum.auth.sso-web-base-url=http://localhost:5173'
```

启动前端：

```bash
pnpm dev:web
```

OA 或独立 main 方法调用的服务端地址仍可以使用：

```text
http://localhost:8080/api/auth/sso/basic-redirection
```

Agentum 返回的 `Location` 应为：

```text
http://localhost:5173/api/auth/sso/basic-consume?code=...
```

本地测试必须统一使用 `localhost`，不要在同一链路中混用 `localhost` 和 `127.0.0.1`，否则 Cookie 和 Local Storage 可能落在不同的浏览器 Origin。

也可以先用命令检查接口是否正确返回 302：

```bash
curl -i --max-redirs 0 \
  -u 'example/zhangsan:这里是共享密码' \
  'http://localhost:8080/api/auth/sso/basic-redirection?portal=business'
```

## 8. 生产部署要求

- Agentum 的 `sso-api-base-url` 必须是用户浏览器可访问的正式 HTTPS 地址。
- Agentum 的 `sso-web-base-url` 必须是正式前端地址；推荐前后端通过 Nginx 同域部署。
- OA 的 Basic 请求地址应指向 Agentum 服务端可访问地址。
- Agentum 允许 IP 应填写 Agentum 实际看到的 OA 或反向代理来源 IP。
- 如果配置允许域名，OA 服务端应显式携带与白名单匹配的 `Origin`。
- 共享密码应使用高强度随机值，并纳入轮换流程。
- Redis 必须稳定可用；交接码不应降级为 JVM 内存存储，否则多实例环境会随机消费失败。
- 日志禁止输出 Authorization、共享密码、一次性交接码、Access Token、Refresh Token 和完整 Cookie。

## 9. 常见问题

| 现象 | 常见原因 | 处理方式 |
| --- | --- | --- |
| 返回 `401` | Basic 头缺失、共享密码错误、凭据格式错误 | 检查 `tenantCode/username:sharedPassword` 和两侧共享密码 |
| 返回 `403` | OA IP / Origin 不在白名单，用户不存在或没有入口角色 | 暂时清空白名单定位问题，再检查用户与 `business` / `tenant_admin` 角色 |
| 返回 `302`，浏览器提示地址无效 | 地址超过 60 秒、已被 HttpClient 自动跟随或已打开一次 | 禁止自动跟随并重新换取地址 |
| 浏览器进入后仍显示登录页 | SSO API / Web 对外地址不一致，或混用 `localhost` 与 `127.0.0.1` | 统一浏览器 Origin，生产使用同域 HTTPS |
| OA 服务端连接超时 | Agentum 地址不可达、防火墙或代理配置错误 | 从 OA 服务器检查 Agentum API 网络连通性 |
| 本地返回 Redis 错误 | Redis 未启动 | 执行 `make dev-infra` 并检查 Redis 状态 |

## 10. Agentum 实现位置

- Basic 换址和消费接口：`apps/api/src/main/java/com/agentum/auth/interfaces/SsoAuthController.java`
- Basic 用户、租户、角色与来源校验：`apps/api/src/main/java/com/agentum/auth/application/SsoAuthService.java`
- Redis 一次性交接码：`apps/api/src/main/java/com/agentum/auth/application/BasicSsoHandoffService.java`
- Spring Security 公开入口：`apps/api/src/main/java/com/agentum/config/SecurityConfiguration.java`
- OpenAPI 契约：`packages/shared-contract/openapi/agentum.openapi.yaml`
