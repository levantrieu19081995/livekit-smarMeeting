import toast from 'react-hot-toast';
import {
  downloadMeetingDocument,
  viewMeetingDocument,
} from '@/services/management/managementDocumentService';
import type { MeetingDocument } from '@/types/management/documents';
import styles from '@/styles/management/Documents.module.css';
import { canPreviewDocument, safeDownloadName } from '@/services/management/documentPreviewSafety';

interface MeetingDocumentsListProps {
  documents: MeetingDocument[];
  emptyText?: string;
  variant?: 'default' | 'compact' | 'detail';
  onDocumentOpened?: () => void;
}

function documentPresentation(document: MeetingDocument) {
  if (document.mimeType === 'application/pdf') {
    return { icon: '📄', label: 'PDF', detailLabel: 'PDF', tone: 'pdf' };
  }
  if (document.mimeType.includes('spreadsheet') || document.mimeType.includes('excel')) {
    return { icon: '📊', label: 'Excel', detailLabel: 'XLS', tone: 'sheet' };
  }
  if (document.mimeType.includes('presentation')) {
    return { icon: '📽', label: 'PowerPoint', detailLabel: 'PPT', tone: 'presentation' };
  }
  return { icon: '📝', label: 'Tài liệu', detailLabel: 'DOC', tone: 'document' };
}

function FileGlyph() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M7 3.5h6l4 4v13H7z" />
      <path d="M13 3.5v4h4" />
    </svg>
  );
}

function EyeGlyph() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6z" />
      <circle cx="12" cy="12" r="2.7" />
    </svg>
  );
}

function DownloadGlyph() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 4v10" />
      <path d="m8 10 4 4 4-4" />
      <path d="M5 20h14" />
    </svg>
  );
}

function formatFileSize(bytes: number) {
  if (bytes >= 1_000_000) return `${(bytes / 1_000_000).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1_000))} KB`;
}

function formatUploadedAt(value: string) {
  return new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

async function openDocument(document: MeetingDocument, onDocumentOpened?: () => void) {
  try {
    const { blob } = await viewMeetingDocument(document.meetingId, document.id);
    if (!canPreviewDocument(blob.type)) {
      await saveDocument(document);
      return;
    }
    const objectUrl = URL.createObjectURL(blob);
    window.open(objectUrl, '_blank', 'noopener,noreferrer');
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000);
    onDocumentOpened?.();
  } catch (error) {
    toast.error(error instanceof Error ? error.message : 'Không thể xem tài liệu.');
  }
}

async function saveDocument(document: MeetingDocument) {
  try {
    const { blob, fileName } = await downloadMeetingDocument(document.meetingId, document.id);
    const objectUrl = URL.createObjectURL(new Blob([blob], { type: 'application/octet-stream' }));
    const link = window.document.createElement('a');
    link.href = objectUrl;
    link.download = safeDownloadName(fileName || document.originalFileName || document.fileName);
    window.document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(objectUrl);
  } catch (error) {
    toast.error(error instanceof Error ? error.message : 'Không thể tải tài liệu.');
  }
}

export function MeetingDocumentsList({
  documents,
  emptyText = 'Chưa có tài liệu nào cho cuộc họp này.',
  variant = 'default',
  onDocumentOpened,
}: MeetingDocumentsListProps) {
  if (documents.length === 0) {
    return <p className={variant === 'detail' ? styles.emptyDetail : styles.empty}>{emptyText}</p>;
  }

  return (
    <div
      className={
        variant === 'detail'
          ? styles.listDetail
          : variant === 'compact'
            ? styles.listCompact
            : styles.list
      }
    >
      {documents.map((document) => {
        const presentation = documentPresentation(document);
        if (variant === 'detail') {
          return (
            <article key={document.id} className={styles.rowDetail}>
              <span
                className={`${styles.fileTypeTile} ${styles[`fileType${presentation.tone}`]}`}
                aria-hidden="true"
              >
                <FileGlyph />
                <span>{presentation.detailLabel}</span>
              </span>
              <div className={styles.detailInfo}>
                <strong className={styles.detailFileName} title={document.originalFileName}>
                  {document.originalFileName}
                </strong>
                <small className={styles.detailMeta}>
                  {presentation.label} · {formatFileSize(document.fileSize)} ·{' '}
                  {document.uploadedBy.displayName} · {formatUploadedAt(document.uploadedAt)}
                </small>
              </div>
              <div className={styles.detailActions}>
                <button
                  type="button"
                  className={styles.detailActionButton}
                  onClick={() => void openDocument(document, onDocumentOpened)}
                  aria-label="Xem tài liệu"
                  title="Xem tài liệu"
                >
                  <EyeGlyph />
                </button>
                <button
                  type="button"
                  className={styles.detailActionButton}
                  onClick={() => void saveDocument(document)}
                  aria-label="Tải xuống"
                  title="Tải xuống"
                >
                  <DownloadGlyph />
                </button>
              </div>
            </article>
          );
        }

        return (
          <article key={document.id} className={styles.row}>
            <div className={styles.info}>
              <span className={styles.icon} aria-hidden="true">
                {presentation.icon}
              </span>
              <div>
                <strong>{document.originalFileName}</strong>
                <small>
                  {presentation.label} · {formatFileSize(document.fileSize)} ·{' '}
                  {document.uploadedBy.displayName} · {formatUploadedAt(document.uploadedAt)}
                </small>
              </div>
            </div>
            <div className={styles.actions}>
              <button type="button" onClick={() => void openDocument(document, onDocumentOpened)}>
                Xem
              </button>
              <button type="button" onClick={() => void saveDocument(document)}>
                {variant === 'compact' ? '↓' : 'Tải xuống'}
              </button>
            </div>
          </article>
        );
      })}
    </div>
  );
}
