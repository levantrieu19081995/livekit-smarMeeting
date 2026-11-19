import type { ReactNode } from 'react';
import type { MeetingMinutesVersion } from '@/types/management/minutes';
import styles from '@/styles/management/Minutes.module.css';

interface MinutesEditorProps {
  mainContent: string;
  conclusion: string;
  versions: MeetingMinutesVersion[];
  nextVersion: number;
  onMainContentChange: (value: string) => void;
  onConclusionChange: (value: string) => void;
  onCancel?: () => void;
  onSaveVersion: () => void;
  saving?: boolean;
  busy?: boolean;
  conclusionAssist?: ReactNode;
}

export function MinutesEditor({
  mainContent,
  conclusion,
  versions,
  nextVersion,
  onMainContentChange,
  onConclusionChange,
  onCancel,
  onSaveVersion,
  saving = false,
  busy = false,
  conclusionAssist,
}: MinutesEditorProps) {
  return (
    <section className={styles.editorLayout}>
      <div className={styles.editorCard}>
        <div className={styles.editorSection}>
          <div className={styles.editorSectionHeader}>
            <h3>Nội dung chính</h3>
            <p>
              Nội dung được tổng hợp từ bản ghi lời nói và trao đổi trong cuộc họp. Bạn có thể hiệu
              chỉnh trực tiếp.
            </p>
          </div>
          <textarea
            className={styles.editorSurface}
            aria-label="Nội dung chính"
            value={mainContent}
            onChange={(event) => onMainContentChange(event.target.value)}
            spellCheck={false}
            disabled={saving || busy}
          />
        </div>

        <div className={styles.aiAssistCard}>
          <div className={styles.aiAssistText}>
            <strong>Bạn có cần tóm tắt nội dung bằng AI không?</strong>
            <p>
              AI có thể hỗ trợ rút gọn và sắp xếp lại nội dung. Nội dung chỉ thay đổi khi bạn chủ
              động sử dụng chức năng này.
            </p>
          </div>
          <button
            type="button"
            className={styles.secondaryButton}
            disabled
            title="Chức năng AI sẽ được kết nối ở bước tiếp theo."
          >
            ✨ Tóm tắt bằng AI
          </button>
        </div>

        <div className={styles.editorSection}>
          <div className={styles.editorSectionHeader}>
            <h3>Kết luận</h3>
            <p>Bạn có thể nhập hoặc hiệu chỉnh kết luận tại đây.</p>
          </div>
          <textarea
            className={`${styles.editorSurface} ${styles.conclusionSurface}`}
            aria-label="Kết luận"
            value={conclusion}
            onChange={(event) => onConclusionChange(event.target.value)}
            spellCheck={false}
            disabled={saving || busy}
          />
          {conclusionAssist}
        </div>
      </div>

      <aside className={styles.versionPanel}>
        <h3>Lịch sử phiên bản</h3>
        <div className={styles.versionList}>
          <article className={`${styles.versionItem} ${styles.versionItemCurrent}`}>
            <strong>Version {nextVersion}</strong>
            <span>Đang hiệu chỉnh</span>
            <small>Chưa lưu</small>
          </article>
          {versions.map((version) => (
            <article key={version.version} className={styles.versionItem}>
              <strong>
                Version {version.version}
                {version.source === 'AI_GENERATED' ? ' (AI)' : ''}
              </strong>
              <span>{version.createdBy}</span>
              <small>
                {new Intl.DateTimeFormat('vi-VN', {
                  dateStyle: 'short',
                  timeStyle: 'short',
                }).format(new Date(version.createdAt))}
              </small>
            </article>
          ))}
        </div>
        <button type="button" className={styles.secondaryButton}>
          Xem tất cả phiên bản
        </button>
      </aside>

      <div className={styles.editActions}>
        {onCancel ? (
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={onCancel}
            disabled={saving || busy}
          >
            Hủy
          </button>
        ) : (
          <span />
        )}
        <button
          type="button"
          className={styles.primaryButton}
          onClick={onSaveVersion}
          disabled={saving || busy}
        >
          {saving ? 'Đang lưu...' : 'Lưu hiệu chỉnh'}
        </button>
      </div>
    </section>
  );
}
