import { useCallback, useEffect, useState } from 'react';
import toast from 'react-hot-toast';
import { MeetingDocumentsList } from '@/components/management/documents/MeetingDocumentsList';
import { MeetingDocumentUpload } from '@/components/management/documents/MeetingDocumentUpload';
import { getMeetingDocuments } from '@/services/management/managementDocumentService';
import type { MeetingDocument } from '@/types/management/documents';
import styles from '@/styles/management/Documents.module.css';

interface MeetingDocumentsPanelProps {
  meetingId: string;
  title?: string;
  compact?: boolean;
  variant?: 'default' | 'detail';
  onClose?: () => void;
}

export function MeetingDocumentsPanel({
  meetingId,
  title = 'Tài liệu cuộc họp',
  compact,
  variant = 'default',
  onClose,
}: MeetingDocumentsPanelProps) {
  const [documents, setDocuments] = useState<MeetingDocument[]>([]);
  const [loading, setLoading] = useState(true);

  const loadDocuments = useCallback(async () => {
    try {
      setLoading(true);
      setDocuments(await getMeetingDocuments(meetingId));
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Không thể tải tài liệu cuộc họp.');
    } finally {
      setLoading(false);
    }
  }, [meetingId]);

  useEffect(() => {
    void loadDocuments();
  }, [loadDocuments]);

  const isDetail = variant === 'detail';

  return (
    <section className={isDetail ? styles.panelDetail : compact ? styles.panelCompact : styles.panel}>
      <div className={isDetail ? styles.panelDetailHeader : styles.panelHeader}>
        {isDetail ? (
          <span className={styles.panelDetailIcon} aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="m21.4 11.6-8.9 8.9a6 6 0 0 1-8.5-8.5l9.5-9.5a4 4 0 1 1 5.7 5.7l-9.6 9.6a2 2 0 0 1-2.8-2.8l8.9-8.9" />
            </svg>
          </span>
        ) : null}
        <h3>{title}</h3>
        {onClose ? (
          <button type="button" className={styles.closeButton} onClick={onClose} aria-label="Đóng">
            ×
          </button>
        ) : null}
        {isDetail ? (
          <div className={styles.panelDetailUpload}>
            <MeetingDocumentUpload
              meetingId={meetingId}
              compact
              onUploaded={(document) => setDocuments((current) => [document, ...current])}
              onUploadSettled={() => void loadDocuments()}
            />
          </div>
        ) : null}
      </div>

      {!isDetail ? (
        <MeetingDocumentUpload
          meetingId={meetingId}
          compact
          onUploaded={(document) => setDocuments((current) => [document, ...current])}
          onUploadSettled={() => void loadDocuments()}
        />
      ) : null}

      {loading ? (
        <p className={isDetail ? styles.emptyDetail : styles.empty}>Đang tải tài liệu...</p>
      ) : (
        <MeetingDocumentsList
          documents={documents}
          variant={isDetail ? 'detail' : compact ? 'compact' : 'default'}
          emptyText="Chưa có tài liệu nào cho cuộc họp này."
        />
      )}
    </section>
  );
}
