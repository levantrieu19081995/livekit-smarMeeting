export type ManagementChatMessageType = 'TEXT';

export interface SaveManagementChatMessageRequest {
  clientMessageId: string;
  content: string;
}

export interface ManagementChatMessageResponse {
  id: number;
  clientMessageId: string;
  senderDisplayName: string;
  messageType: ManagementChatMessageType;
  content: string;
  sentAt: string;
  senderConnectionId?: string | null;
}

export interface ManagementChatHistoryPage {
  content: ManagementChatMessageResponse[];
  hasMore: boolean;
  nextAfterId: number | null;
}
