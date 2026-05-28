-- 阶段一移除运行审计 demo 页签：清理本地演示数据中的页签分配明细。
DELETE FROM page_grants WHERE page_key = 'audit';
