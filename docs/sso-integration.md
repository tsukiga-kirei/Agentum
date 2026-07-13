# 企业 SSO 对接说明

本文说明 Agentum 企业 SSO 的设计边界、业务系统配合方式和当前实现状态。

需要按 OA Java / HttpClient 模式落地 Basic 单点登录时，参见 [OA Basic 单点登录对接示例](./oa-basic-sso-integration.md)。

## 1. 设计原则

Agentum 将 SSO 定位为“外部身份认证来源”，不是业务权限来源。

外部身份源只负责证明“这个人是谁”。登录成功后，Agentum 仍然按本地的租户、入口角色、成员关系、部门、租户内角色、资源范围和能力池分配重新计算权限。前端展示的菜单、模块和页签也仍以后端返回的 `LoginResponse` 为准。

当前优先支持两类租户级企业认证方式，并在同一租户内二选一启用：

- `oidc`：标准 OAuth2 / OIDC 授权码登录。
- `basic`：面向已信任业务系统的入站 Basic 单点入口。业务系统使用租户编号与用户账号构造 Basic 用户名，并使用系统管理员在 Agentum 配置的共享密码完成单点登录。

SAML、SCIM、MFA、单点登出和自动用户全量同步属于后续增强。

## 2. 对方系统最小配合

为了让业务系统开发代码尽量少，推荐业务系统不要为 Agentum 写专用登录接口，而是接入或启用标准 OIDC Provider。

### 2.1 OAuth2 / OIDC

对方系统或身份平台需要提供：

| 项目 | 说明 |
| --- | --- |
| `issuer` | OIDC Issuer，必须与 `id_token` 的 `iss` 一致 |
| `clientId` | 分配给 Agentum 的应用 ID |
| `clientSecret` | 分配给 Agentum 的应用密钥，由 Agentum 加密保存 |
| `authorizationEndpoint` | OIDC 授权地址 |
| `tokenEndpoint` | OIDC code 换 token 地址 |
| `jwksUri` | 身份令牌签名公钥地址，当前用于配置留痕，运行时按 issuer 做 OIDC 发现 |
| `redirectUri` 白名单 | Agentum 回调地址 |
| 用户 claims | 至少返回 `sub`，建议返回 `email`、`name` |

Agentum 回调地址格式：

```text
https://agentum.example.com/api/auth/sso/callback/{providerId}
```

其中 `{providerId}` 是 Agentum 中该租户 SSO Provider 的 ID。

对方系统不需要：

- 判断 Agentum 的业务权限。
- 维护 Agentum 的租户角色、部门、资源范围或能力池。
- 签发 Agentum 业务 token。
- 调用 Agentum 创建会话。
- 实现 Agentum 专用登录协议。

如果对方已有 Keycloak、Authing、Azure AD、Okta、企业微信、钉钉、飞书等身份平台，通常只需要创建一个 OIDC 应用并配置回调白名单。

### 2.2 Basic 单点入口

Basic 方式用于业务系统已经完成用户身份确认，但不希望把用户个人密码交给 Agentum 的场景。它不是让用户在 Agentum 登录页输入业务系统密码，而是由可信业务系统服务端携带共享凭据访问 Agentum 单点入口。

Agentum Basic 单点入口分为两种：

```text
https://agentum.example.com/api/auth/sso/basic-entry?portal=business
```

其中 `basic-entry` 用于浏览器已经能直接携带 Basic 请求头的受控场景。OA 等跨域业务系统应调用服务端换址入口：

```text
https://agentum.example.com/api/auth/sso/basic-redirection?portal=business
```

`portal` 当前支持：

- `business`
- `tenant_admin`

Basic 凭据规则：

| 项目 | 说明 |
| --- | --- |
| Basic username | `tenantCode/username`，例如 `cloudway/operator`。Basic 协议本身用冒号分隔密码，因此租户编号和用户名之间使用 `/` |
| Basic password | 系统管理员在该租户企业认证配置中维护的共享密码，Agentum 加密保存 |
| 来源白名单 | 可配置允许 IP 和允许域名；为空表示不按该维度限制 |

示例：

```bash
curl -i -u 'cloudway/operator:shared-secret' \
  'https://agentum.example.com/api/auth/sso/basic-redirection?portal=business'
```

`basic-entry` 成功后，Agentum 会返回与 OIDC 回调一致的登录桥接页：写入 HttpOnly Refresh Cookie，并通过前端桥接写入短期 Access Token。

`basic-redirection` 成功后只返回 `302 Location`。业务系统服务端将该 Location 原样重定向给浏览器，浏览器再访问 Agentum 的一次性地址建立 Cookie 会话。交接码默认有效 60 秒、只能消费一次，Redis 中仅保存用户、租户、身份源和入口上下文，不保存共享密码或 Token。业务系统不需要也不应该签发 Agentum 业务 Token。

## 3. Agentum 侧配置

当前数据库表为：

- `tenant_sso_providers`：租户企业认证身份源配置。
- `user_external_identities`：外部身份 `sub` 与 Agentum 用户的绑定关系。

核心字段：

```text
tenant_sso_providers
- tenant_id
- provider_type = oidc / basic
- name
- status = enabled
- issuer
- client_id
- encrypted_client_secret
- authorization_endpoint
- token_endpoint
- jwks_uri
- auto_bind_email
- encrypted_basic_password
- allowed_ip_ranges
- allowed_domains
```

`encrypted_client_secret` 和 `encrypted_basic_password` 必须通过后端加密服务写入，禁止在文档、日志、前端响应或迁移脚本中保存明文。

## 4. 登录流程

### 4.1 OAuth2 / OIDC

```text
用户在登录页选择租户和入口
  -> Agentum 查询该租户启用的 SSO Provider
  -> 用户点击企业 SSO
  -> Agentum 生成短期签名 state 和 nonce
  -> 浏览器跳转到对方 OIDC authorizationEndpoint
  -> 对方系统完成登录并回调 Agentum
  -> Agentum 使用 code 调 tokenEndpoint 换取 id_token
  -> Agentum 校验签名、issuer、audience、nonce、过期时间和 sub
  -> Agentum 根据 providerId + sub 查找或绑定本地用户
  -> Agentum 校验 tenantId + portal 对应的 user_role_assignments
  -> Agentum 签发自己的 Bearer Token，并返回 LoginResponse
```

这条链路中，对方系统只参与标准 OIDC 登录。Agentum 自己完成租户、角色、菜单和权限计算。

### 4.2 Basic 单点入口

```text
业务系统确认当前用户身份
  -> 业务系统服务端用 tenantCode/username + 共享密码访问 Agentum Basic 换址入口
  -> Agentum 校验共享密码、来源 IP / 域名白名单
  -> Agentum 用 tenantCode 定位租户，用 username 定位本地用户
  -> Agentum 校验该用户在当前租户拥有 portal 对应的 user_role_assignments
  -> Agentum 返回短期、一次性的浏览器登录地址
  -> 业务系统将该地址重定向给浏览器
  -> 浏览器访问 Agentum 域名，Agentum 签发自己的 Bearer Token、写入 Refresh Cookie，并通过登录桥接页交给前端
```

Basic 方式不使用用户个人密码，也不自动创建用户。租户管理员仍需先在 Agentum 里维护用户、成员关系和入口角色。

## 5. 账号绑定规则

Agentum 统一用本地 `username` 做企业认证绑定口径，避免同一账号在不同系统里因邮箱、姓名或外部组变化导致权限漂移。

OIDC 方式按以下顺序识别用户：

1. 优先使用 `provider_id + sub` 查询 `user_external_identities`。
2. 如果没有绑定，使用 `preferred_username` / `username` / `sub` 匹配 Agentum 本地 `users.username`。
3. 找到用户后仍必须校验该用户在当前租户拥有所选入口角色。
4. 无法匹配或无入口权限时拒绝登录。

当前版本不自动创建 Agentum 用户。租户管理员仍需先在人员组织中创建用户、成员关系和入口角色，避免外部身份源直接扩张本地权限边界。

Basic 方式按 `tenantCode/username` 中的 `username` 匹配 Agentum 本地用户，并在首次成功登录后把该用户名写入 `user_external_identities.subject` 形成绑定记录。找到用户后仍必须校验该用户在当前租户拥有所选入口角色。

## 6. 前端接口

登录页使用：

```http
GET /api/public/tenants/{tenantId}/sso-providers
```

返回示例：

```json
[
  {
    "id": "00000000-0000-0000-0000-000000000901",
    "name": "企业统一身份",
    "providerType": "oidc"
  }
]
```

发起 OIDC 登录：

```http
GET /api/auth/sso/authorize?tenantId={tenantId}&providerId={providerId}&portal=business
```

支持的 `portal`：

- `business`
- `tenant_admin`

系统管理员入口当前不走租户 SSO。平台管理员应保留本地应急账号，后续再接入 MFA 或平台级 SSO 策略。

Basic 服务端换址入口：

```http
GET /api/auth/sso/basic-redirection?portal=business
Authorization: Basic base64(tenantCode/username:shared-secret)
```

业务系统收到 `302 Location` 后应把该地址重定向给用户浏览器；不要把 Basic 共享密码拼入 URL，也不要试图把服务端 HttpClient 获得的 Cookie 复制给浏览器。

登录页在租户启用 Basic 时只提示“请从已授权业务系统进入 Agentum”；不会要求用户输入共享密码。

## 7. 安全要求

- `state` 必须短期有效并带签名，防止篡改和跨租户回调。
- `nonce` 必须写入授权请求并校验 `id_token`。
- `id_token` 必须校验 `iss`、`aud`、`exp`、签名和 `sub`。
- 日志只记录 `providerId`、`tenantId`、`userId`、`requestId` 等追踪字段，禁止输出 token、client secret、完整 claims 或 Cookie。
- 外部 `groups` 只能作为后续角色映射输入，不能直接绕过 Agentum 的权限模型。
- SSO 登录成功后，所有业务接口仍按 Bearer Token 中的活跃租户和角色重新校验。
- Basic 共享密码必须由系统管理员配置并加密保存，业务系统服务端持有，禁止下发给浏览器前端。
- Basic 入口建议同时配置允许 IP 或允许域名；生产环境必须通过 HTTPS 访问。

## 8. 当前实现状态

已实现：

- 租户 OIDC Provider 与外部身份绑定表结构。
- 登录页按租户发现 SSO Provider。
- OIDC 授权跳转、签名 `state`、`nonce`。
- OIDC 回调换取并校验 `id_token`。
- 外部身份绑定到本地用户后签发 Agentum Access Token，并通过 HttpOnly Cookie 建立可轮换的 Refresh Token 会话。
- 回调页通过 `postMessage` 把含 Access Token 的 `LoginResponse` 交回前端，Refresh Token 只通过 Set-Cookie 写入，二者都不会出现在 URL 查询串。
- 租户级企业认证配置支持在系统管理租户抽屉中启停，并在 OAuth2/OIDC 与 Basic 之间二选一。
- Basic 单点入口支持共享密码、来源 IP 和来源域名限制，以及服务端换取一次性浏览器登录地址。

待后续实现：

- 租户管理中的外部组到部门 / 租户内角色映射。
- JIT 自动创建用户策略。
- SAML、SCIM、MFA、OIDC Provider 单点登出、登录设备管理和全端下线；Agentum 本地 Refresh Token 轮换与单会话吊销已经落地。
