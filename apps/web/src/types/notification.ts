import type { PageResponse } from "./organization";

export type NotificationStatusFilter = "all" | "unread" | "read";

export type NotificationUnreadCount = {
  unreadCount: number;
};

export type NotificationRow = {
  id: string;
  category: "system_notice" | "schedule_result" | string;
  scope: "global" | "tenant" | "user" | string;
  title: string;
  contentMarkdown: string;
  unread: boolean;
  publisherName: string;
  createdAt: string;
  readAt: string | null;
};

export type NotificationPage = PageResponse<NotificationRow>;

export type PublishAnnouncementRequest = {
  scope: "global" | "tenant";
  tenantId?: string | null;
  title: string;
  contentMarkdown: string;
};
