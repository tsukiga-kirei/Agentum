# events

这里存放工作流运行、节点暂停恢复、人工审核、交付结果等事件契约。

| 文件 | 说明 |
| --- | --- |
| `runtime-events.schema.json` | SSE 流式事件（agent_streaming、node_completed 等） |
| `node-execute-command.schema.json` | RabbitMQ 节点执行命令（见 `docs/runtime-async-execution-design.md`） |

