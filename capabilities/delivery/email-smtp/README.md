# SMTP 邮箱交付

当前阶段唯一保留的交付通道是邮箱。系统管理登记交付能力时，配置字段应与 `manifest.yaml` 保持一致：

- `smtpHost`
- `smtpPort`
- `smtpUsername`
- `smtpPassword`
- `fromAddress`
- `useTls`

本地测试可使用项目基础设施里的 Mailpit：

- SMTP：`localhost:1025`
- 控制台：`http://localhost:8025`

密码由后端加密保存，接口只回显是否已配置，不返回明文或密文。
