# 自定义 OA 流程交付

这是自定义交付适配器的协议示例，用于说明 `capabilities/delivery/` 目录应如何声明外部交付能力。

系统管理登记时可填写：

- 来源：`自定义适配器`
- 实现标识：`custom-oa-delivery`
- Manifest 路径：`capabilities/delivery/custom-oa-delivery/manifest.yaml`
- 协议：`HTTP 适配器`
- 调用入口：`http://localhost:19090/delivery`

该示例不包含真实 OA 调用代码。真实适配器应在本目录补充 `src/` 与 `tests/`，并确保不硬编码租户密钥、Token 或生产地址。
