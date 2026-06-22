# 企业 SSO 对接说明

本文说明 Agentum 企业 SSO 的设计边界、业务系统配合方式和当前实现状态。

## 1. 设计原则

Agentum 将 SSO 定位为“外部身份认证来源”，不是业务权限来源。

外部身份源只负责证明“这个人是谁”。登录成功后，Agentum 仍然按本地的租户、入口角色、成员关系、部门、租户内角色、资源范围和能力池分配重新计算权限。前端展示的菜单、模块和页签也仍以后端返回的 `LoginResponse` 为准。

当前优先支持标准 OIDC。SAML、SCIM、MFA、单点登出和自动用户全量同步属于后续增强。

## 2. 对方系统最小配合

为了让业务系统开发代码尽量少，推荐业务系统不要为 Agentum 写专用登录接口，而是接入或启用标准 OIDC Provider。

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

## 3. Agentum 侧配置

当前数据库表为：

- `tenant_sso_providers`：租户 OIDC 身份源配置。
- `user_external_identities`：外部身份 `sub` 与 Agentum 用户的绑定关系。

核心字段：

```text
tenant_sso_providers
- tenant_id
- provider_type = oidc
- name
- status = enabled
- issuer
- client_id
- encrypted_client_secret
- authorization_endpoint
- token_endpoint
- jwks_uri
- email_domain
- auto_bind_email
```

`encrypted_client_secret` 必须通过后端加密服务写入，禁止在文档、日志、前端响应或迁移脚本中保存明文。

## 4. 登录流程

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

## 5. 账号绑定规则

Agentum 按以下顺序识别用户：

1. 优先使用 `provider_id + sub` 查询 `user_external_identities`。
2. 如果没有绑定，且租户 Provider 开启 `auto_bind_email`，使用 `email` 匹配已有 Agentum 用户。
3. 如果配置了 `email_domain`，邮箱必须属于该域名。
4. 找到用户后仍必须校验该用户在当前租户拥有所选入口角色。
5. 无法匹配或无入口权限时拒绝登录。

当前版本不自动创建 Agentum 用户。租户管理员仍需先在人员组织中创建用户、成员关系和入口角色，避免外部身份源直接扩张本地权限边界。

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

发起登录：

```http
GET /api/auth/sso/authorize?tenantId={tenantId}&providerId={providerId}&portal=business
```

支持的 `portal`：

- `business`
- `tenant_admin`

系统管理员入口当前不走租户 SSO。平台管理员应保留本地应急账号，后续再接入 MFA 或平台级 SSO 策略。

## 7. 安全要求

- `state` 必须短期有效并带签名，防止篡改和跨租户回调。
- `nonce` 必须写入授权请求并校验 `id_token`。
- `id_token` 必须校验 `iss`、`aud`、`exp`、签名和 `sub`。
- 日志只记录 `providerId`、`tenantId`、`userId`、`requestId` 等追踪字段，禁止输出 token、client secret、完整 claims 或 Cookie。
- 外部 `groups` 只能作为后续角色映射输入，不能直接绕过 Agentum 的权限模型。
- SSO 登录成功后，所有业务接口仍按 Bearer Token 中的活跃租户和角色重新校验。

## 8. 当前实现状态

已实现：

- 租户 OIDC Provider 与外部身份绑定表结构。
- 登录页按租户发现 SSO Provider。
- OIDC 授权跳转、签名 `state`、`nonce`。
- OIDC 回调换取并校验 `id_token`。
- 外部身份绑定到本地用户后签发 Agentum Access Token，并通过 HttpOnly Cookie 建立可轮换的 Refresh Token 会话。
- 回调页通过 `postMessage` 把含 Access Token 的 `LoginResponse` 交回前端，Refresh Token 只通过 Set-Cookie 写入，二者都不会出现在 URL 查询串。

待后续实现：

- 系统管理页面中的 SSO Provider 配置表单。
- 租户管理中的外部组到部门 / 租户内角色映射。
- JIT 自动创建用户策略。
- SAML、SCIM、MFA、OIDC Provider 单点登出、登录设备管理和全端下线；Agentum 本地 Refresh Token 轮换与单会话吊销已经落地。
