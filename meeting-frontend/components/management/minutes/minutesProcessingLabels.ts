import type { MinutesProcessingStage } from '@/types/management/minutes';

export const MINUTES_PROCESSING_UI = {
  VALIDATING: {
    stepLabel: 'Kiểm tra dữ liệu ghi âm',
    processingText: 'Đang kiểm tra dữ liệu ghi âm',
  },
  PREPARING_AUDIO: {
    stepLabel: 'Chuẩn bị các đoạn ghi âm',
    processingText: 'Đang chuẩn bị các đoạn ghi âm',
  },
  TRANSCRIBING: {
    stepLabel: 'Chuyển giọng nói thành văn bản',
    processingText: 'Đang chuyển giọng nói thành văn bản',
  },
  BUILDING_TRANSCRIPT: {
    stepLabel: 'Kết hợp bản ghi lời nói và trao đổi',
    processingText: 'Đang kết hợp bản ghi lời nói và trao đổi',
  },
  GENERATING_MINUTES: {
    stepLabel: 'Tạo nội dung biên bản ban đầu',
    processingText: 'Đang tạo nội dung biên bản ban đầu',
  },
  DRAFT_READY: {
    stepLabel: 'Version 1 đã sẵn sàng',
    processingText: 'Biên bản đã sẵn sàng',
  },
  COMPLETED: {
    stepLabel: 'Hoàn thành',
    processingText: 'Hoàn thành',
  },
  FAILED: {
    stepLabel: 'Xử lý thất bại',
    processingText: 'Xử lý thất bại',
  },
} satisfies Record<MinutesProcessingStage, { stepLabel: string; processingText: string }>;
