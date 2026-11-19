import { useRef, useState } from 'react';
import toast from 'react-hot-toast';
import { uploadMeetingDocument } from '@/services/management/managementDocumentService';
import type { LocalMeetingDocument, MeetingDocument } from '@/types/management/documents';
import styles from '@/styles/management/Documents.module.css';

interface MeetingDocumentUploadProps {
  meetingId?: string;
  selectedFiles?: LocalMeetingDocument[];
  disabled?: boolean;
  compact?: boolean;
  variant?: 'default' | 'meeting-edit' | 'meeting-create';
  onLocalFilesChange?: (files: LocalMeetingDocument[]) => void;
  onUploaded?: (document: MeetingDocument) => void;
  onUploadSettled?: () => void;
}

function createLocalDocument(file: File): LocalMeetingDocument {
  return {
    id: `local-${Date.now()}-${crypto.randomUUID?.() ?? file.name}`,
    name: file.name,
    size: file.size,
    contentType: file.type,
    file,
  };
}

function formatDocumentSize(size?: number) {
  if (!size) return 'Không có kích thước';
  if (size >= 1_000_000) return `${(size / 1_000_000).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(size / 1_000))} KB`;
}

export function MeetingDocumentUpload({
  meetingId,
  selectedFiles = [],
  disabled,
  compact,
  variant = 'default',
  onLocalFilesChange,
  onUploaded,
  onUploadSettled,
}: MeetingDocumentUploadProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const canUploadImmediately = meetingId != null && onUploaded != null;

  async function handleFiles(files: File[]) {
    if (files.length === 0) return;
    if (!canUploadImmediately) {
      onLocalFilesChange?.([...selectedFiles, ...files.map(createLocalDocument)]);
      return;
    }

    setUploading(true);
    const results = await Promise.allSettled(
      files.map((file) => uploadMeetingDocument(meetingId, file)),
    );
    let successCount = 0;
    for (const result of results) {
      if (result.status === 'fulfilled') {
        successCount += 1;
        onUploaded(result.value);
      }
    }
    if (successCount > 0) {
      toast.success(
        successCount === 1
          ? 'Đã tải tài liệu lên cuộc họp.'
          : `Đã tải ${successCount} tài liệu lên cuộc họp.`,
      );
    }
    if (successCount < results.length) {
      toast.error('Một số tài liệu tải lên không thành công.');
    }
    setUploading(false);
    onUploadSettled?.();
  }

  function handleChange(event: React.ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []);
    event.target.value = '';
    void handleFiles(files);
  }

  function removeLocalFile(id: string) {
    onLocalFilesChange?.(selectedFiles.filter((file) => file.id !== id));
  }

  if (variant === 'meeting-edit') {
    return (
      <div className={styles.uploadMeetingEdit}>
        <button
          type="button"
          className={styles.uploadMeetingEditButton}
          disabled={disabled || uploading}
          onClick={() => inputRef.current?.click()}
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => {
            event.preventDefault();
            void handleFiles(Array.from(event.dataTransfer.files));
          }}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 5v14" />
            <path d="M5 12h14" />
          </svg>
          <strong>{uploading ? 'Đang tải...' : 'Thêm tài liệu'}</strong>
        </button>
        <input
          ref={inputRef}
          className={styles.hiddenInput}
          type="file"
          multiple
          disabled={disabled || uploading}
          onChange={handleChange}
        />
      </div>
    );
  }

  return (
    <div className={compact ? styles.uploadCompact : styles.upload}>
      <button
        type="button"
        className={styles.uploadDropzone}
        disabled={disabled || uploading}
        onClick={() => inputRef.current?.click()}
        onDragOver={(event) => event.preventDefault()}
        onDrop={(event) => {
          event.preventDefault();
          void handleFiles(Array.from(event.dataTransfer.files));
        }}
      >
        {variant === 'meeting-create' ? (
          <svg
            className={styles.uploadCreateIcon}
            viewBox="0 0 24 24"
            aria-hidden="true"
            focusable="false"
          >
            <path d="M14 2.5H6a2 2 0 0 0-2 2v15a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-11L14 2.5Z" />
            <path d="M14 2.5v6h6M12 18v-7m-3 3 3-3 3 3" />
          </svg>
        ) : (
          <span aria-hidden="true">📎</span>
        )}
        <strong>
          {uploading ? 'Đang tải...' : compact ? '+ Thêm tài liệu' : 'Kéo thả file vào đây'}
        </strong>
        {!compact ? <small>hoặc Chọn tài liệu</small> : null}
      </button>
      <input
        ref={inputRef}
        className={styles.hiddenInput}
        type="file"
        multiple
        disabled={disabled || uploading}
        onChange={handleChange}
      />

      {selectedFiles.length > 0 ? (
        <div className={styles.localList}>
          {selectedFiles.map((document) => (
            <div key={document.id} className={styles.localItem}>
              <div>
                <strong>{document.name}</strong>
                <small>{formatDocumentSize(document.size)}</small>
              </div>
              <button
                type="button"
                onClick={() => removeLocalFile(document.id)}
                disabled={disabled}
              >
                ×
              </button>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}
