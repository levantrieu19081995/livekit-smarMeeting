import { useEffect, useState } from 'react';
import toast from 'react-hot-toast';
import {
  downloadMinutesPdf,
  getMinutesProcessing,
  getMinutesTemplates,
  renderMinutesVersion,
} from '@/services/management/managementMinutesService';
import { MINUTES_PROCESSING_UI } from './minutesProcessingLabels';
import type {
  MeetingMinutesListItem,
  MeetingMinutesVersion,
  MinutesProcessingApiResponse,
  MinutesProcessingStage,
  MinutesRenderApiResponse,
  MinutesTemplateApiResponse,
} from '@/types/management/minutes';
import styles from '@/styles/management/Minutes.module.css';

interface DialogFrameProps {
  title: string;
  children: React.ReactNode;
  onClose: () => void;
  className?: string;
}

function DialogFrame({ title, children, onClose, className }: DialogFrameProps) {
  return (
    <div className={styles.dialogOverlay} role="presentation" onMouseDown={onClose}>
      <section
        className={`${styles.dialog} ${className ?? ''}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="minutes-dialog-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className={styles.dialogHeader}>
          <h3 id="minutes-dialog-title">{title}</h3>
          <button type="button" className={styles.iconButton} aria-label="Đóng" onClick={onClose}>
            ×
          </button>
        </div>
        {children}
      </section>
    </div>
  );
}

export function GenerateMinutesDialog({
  meeting,
  onClose,
  onConfirm,
}: {
  meeting: MeetingMinutesListItem;
  onClose: () => void;
  onConfirm: () => void;
}) {
  return (
    <DialogFrame title="Tạo biên bản cuộc họp" onClose={onClose}>
      <div className={styles.dialogBody}>
        <strong>{meeting.title}</strong>
        <p>
          Hệ thống sẽ xử lý bản ghi âm, chuyển giọng nói thành văn bản, kết hợp nội dung trao đổi và
          tạo nội dung biên bản ban đầu để bạn xem và hiệu chỉnh.
        </p>
        <p>Quá trình này có thể mất vài phút.</p>
        <div className={styles.dialogNote}>Yêu cầu sẽ được gửi đến Java meeting-service.</div>
      </div>
      <div className={styles.dialogActions}>
        <button type="button" className={styles.secondaryButton} onClick={onClose}>
          Hủy
        </button>
        <button type="button" className={styles.primaryButton} onClick={onConfirm}>
          Bắt đầu xử lý
        </button>
      </div>
    </DialogFrame>
  );
}

export function ProcessingMinutesDialog({
  meeting,
  onClose,
  onDraftReady,
}: {
  meeting: MeetingMinutesListItem;
  onClose: () => void;
  onDraftReady?: () => void;
}) {
  const [processing, setProcessing] = useState<MinutesProcessingApiResponse | null>(null);
  const progress = processing?.progressPercent ?? meeting.progress ?? 0;
  const draftReady = processing?.stage === 'DRAFT_READY' && processing?.status !== 'FAILED';

  useEffect(() => {
    let active = true;
    let timer: number | undefined;

    async function loadProcessing() {
      try {
        const response = await getMinutesProcessing(meeting.meetingId);
        if (!active) return;

        setProcessing(response);

        if (
          response.status === 'COMPLETED' ||
          response.status === 'FAILED' ||
          response.stage === 'DRAFT_READY'
        ) {
          active = false;
          if (timer) window.clearInterval(timer);
        }
      } catch (error) {
        if (active) {
          toast.error(error instanceof Error ? error.message : 'Không thể tải tiến độ xử lý.');
        }
      }
    }

    timer = window.setInterval(() => void loadProcessing(), 4_000);
    void loadProcessing();
    return () => {
      active = false;
      if (timer) window.clearInterval(timer);
    };
  }, [meeting.meetingId]);

  const steps = buildProcessingSteps(processing?.stage);

  return (
    <DialogFrame title="Đang tạo biên bản" onClose={onClose}>
      <div className={styles.dialogBody}>
        <strong>{draftReady ? 'Version 1 đã sẵn sàng' : meeting.title}</strong>
        <div
          className={styles.processingProgress}
          role="progressbar"
          aria-valuenow={progress}
          aria-valuemin={0}
          aria-valuemax={100}
        >
          <span style={{ width: `${progress}%` }} />
        </div>
        <div className={styles.processingSteps}>
          {steps.map((step) => (
            <div
              key={step.label}
              className={`${styles.processingStep} ${styles[`processing${step.state}`]}`}
            >
              <span aria-hidden="true">
                {step.state === 'done' ? '✓' : step.state === 'active' ? '●' : '○'}
              </span>
              <p>{step.label}</p>
            </div>
          ))}
        </div>
        <p>
          {(processing?.stage
            ? MINUTES_PROCESSING_UI[processing.stage]?.processingText
            : null) ||
            processing?.errorMessage ||
            (draftReady
              ? 'Version 1 đã được tạo và sẵn sàng để xem, hiệu chỉnh.'
              : 'Bạn có thể rời trang này. Quá trình xử lý sẽ tiếp tục trong nền.')}
        </p>
      </div>
      <div className={styles.dialogActions}>
        {draftReady && onDraftReady ? (
          <button type="button" className={styles.primaryButton} onClick={onDraftReady}>
            Mở biên bản
          </button>
        ) : null}
        <button type="button" className={styles.primaryButton} onClick={onClose}>
          Đóng
        </button>
      </div>
    </DialogFrame>
  );
}

function buildProcessingSteps(stage: MinutesProcessingStage | undefined) {
  const stages: Array<{ stage: MinutesProcessingStage; label: string }> = [
    { stage: 'VALIDATING', label: MINUTES_PROCESSING_UI.VALIDATING.stepLabel },
    { stage: 'PREPARING_AUDIO', label: MINUTES_PROCESSING_UI.PREPARING_AUDIO.stepLabel },
    { stage: 'TRANSCRIBING', label: MINUTES_PROCESSING_UI.TRANSCRIBING.stepLabel },
    { stage: 'BUILDING_TRANSCRIPT', label: MINUTES_PROCESSING_UI.BUILDING_TRANSCRIPT.stepLabel },
    { stage: 'GENERATING_MINUTES', label: MINUTES_PROCESSING_UI.GENERATING_MINUTES.stepLabel },
    { stage: 'DRAFT_READY', label: MINUTES_PROCESSING_UI.DRAFT_READY.stepLabel },
  ];
  const activeIndex = Math.max(
    0,
    stages.findIndex((item) => item.stage === stage),
  );
  return stages.map((item, index) => ({
    label: item.label,
    state:
      stage === 'FAILED'
        ? index < activeIndex
          ? 'done'
          : index === activeIndex
            ? 'active'
            : 'pending'
        : index < activeIndex
          ? 'done'
          : index === activeIndex
            ? 'active'
            : 'pending',
  }));
}

function exportStatusLabel(status: MeetingMinutesVersion['status']) {
  if (status === 'FINAL') return 'Đã hoàn tất';
  if (status === 'DRAFT') return 'Bản nháp';
  return status ?? 'Chưa xác định';
}

export function MinutesExportDialog({
  meetingId,
  version,
  onClose,
}: {
  meetingId: string;
  version: MeetingMinutesVersion;
  onClose: () => void;
}) {
  const [templates, setTemplates] = useState<MinutesTemplateApiResponse[]>([]);
  const [selectedTemplateCode, setSelectedTemplateCode] = useState('');
  const [templatesLoading, setTemplatesLoading] = useState(true);
  const [renderLoading, setRenderLoading] = useState(false);
  const [pdfLoading, setPdfLoading] = useState(false);
  const [renderResult, setRenderResult] = useState<MinutesRenderApiResponse | null>(null);

  const selectedTemplate = templates.find((template) => template.code === selectedTemplateCode);

  useEffect(() => {
    let active = true;

    async function loadTemplates() {
      try {
        setTemplatesLoading(true);
        const response = await getMinutesTemplates();
        if (!active) return;
        setTemplates(response);
        setSelectedTemplateCode(response[0]?.code ?? '');
      } catch (error) {
        if (active) {
          toast.error(error instanceof Error ? error.message : 'Không thể tải danh sách biểu mẫu.');
        }
      } finally {
        if (active) setTemplatesLoading(false);
      }
    }

    void loadTemplates();
    return () => {
      active = false;
    };
  }, []);

  async function handleRender() {
    if (!selectedTemplateCode || renderLoading) return;

    try {
      setRenderLoading(true);
      const response = await renderMinutesVersion(meetingId, version.version, selectedTemplateCode);
      setRenderResult(response);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Không thể tạo bản xem trước biên bản.');
    } finally {
      setRenderLoading(false);
    }
  }

  async function handleExportPdf() {
    if (!renderResult || !renderResult.canExportPdf || pdfLoading) {
      return;
    }

    try {
      setPdfLoading(true);
      const result = await downloadMinutesPdf(
        meetingId,
        version.version,
        renderResult.template.code,
      );
      const objectUrl = URL.createObjectURL(result.blob);
      const anchor = document.createElement('a');

      try {
        anchor.href = objectUrl;
        anchor.download = result.filename;
        anchor.style.display = 'none';
        document.body.appendChild(anchor);
        anchor.click();
      } finally {
        anchor.remove();
        URL.revokeObjectURL(objectUrl);
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Không thể xuất PDF biên bản.');
    } finally {
      setPdfLoading(false);
    }
  }

  return (
    <DialogFrame title="Xuất biên bản" onClose={onClose} className={styles.exportDialog}>
      <div className={styles.exportDialogBody}>
        <div className={styles.exportTemplateControls}>
          <div>
            <span>Phiên bản</span>
            <strong>Version {version.version}</strong>
          </div>
          <div>
            <span>Trạng thái</span>
            <strong>{exportStatusLabel(version.status)}</strong>
          </div>
          <label className={styles.exportTemplateField}>
            <span>Biểu mẫu</span>
            <select
              value={selectedTemplateCode}
              onChange={(event) => {
                setSelectedTemplateCode(event.target.value);
                setRenderResult(null);
              }}
              disabled={templatesLoading || templates.length === 0}
            >
              {templates.map((template) => (
                <option key={template.code} value={template.code}>
                  {template.name}
                </option>
              ))}
            </select>
          </label>
        </div>

        {templatesLoading ? (
          <p className={styles.exportTemplateDescription}>Đang tải danh sách biểu mẫu...</p>
        ) : templates.length === 0 ? (
          <p className={styles.exportTemplateDescription}>Chưa có biểu mẫu khả dụng.</p>
        ) : selectedTemplate ? (
          <p className={styles.exportTemplateDescription}>{selectedTemplate.description}</p>
        ) : null}

        <p className={styles.exportStatusNote}>
          {version.status === 'FINAL'
            ? 'Đây là phiên bản đã hoàn tất.'
            : 'Phiên bản chưa hoàn tất. Bản xem trước sẽ có watermark BẢN NHÁP.'}
        </p>

        <button
          type="button"
          className={styles.primaryButton}
          onClick={handleRender}
          disabled={templatesLoading || templates.length === 0 || renderLoading}
        >
          {renderLoading ? 'Đang tạo bản xem trước...' : 'Xem trước'}
        </button>

        <div className={styles.exportPreview}>
          {renderResult ? (
            <iframe
              title={`Xem trước biên bản Version ${version.version}`}
              srcDoc={renderResult.html}
              sandbox=""
              className={styles.exportPreviewFrame}
            />
          ) : (
            <div className={styles.exportPreviewPlaceholder}>
              Chọn biểu mẫu và tạo bản xem trước.
            </div>
          )}
        </div>

        {renderResult ? (
          <p className={styles.exportPdfNote}>
            Hiện tại hệ thống hỗ trợ xem trước biểu mẫu. Xuất PDF sẽ được bật khi backend hỗ trợ.
          </p>
        ) : null}
      </div>
      <div className={styles.dialogActions}>
        <button type="button" className={styles.secondaryButton} onClick={onClose}>
          Đóng
        </button>
        {renderResult ? (
          <button
            type="button"
            className={styles.primaryButton}
            onClick={handleExportPdf}
            disabled={!renderResult || !renderResult.canExportPdf || pdfLoading}
            title={
              renderResult.canExportPdf
                ? undefined
                : 'Chức năng xuất PDF sẽ được hỗ trợ ở bước tiếp theo.'
            }
          >
            {pdfLoading ? 'Đang xuất PDF...' : 'Xuất PDF'}
          </button>
        ) : null}
      </div>
    </DialogFrame>
  );
}

export function FinalizeMinutesDialog({
  onClose,
  onConfirm,
}: {
  onClose: () => void;
  onConfirm: () => void;
}) {
  return (
    <DialogFrame title="Hoàn tất biên bản?" onClose={onClose}>
      <div className={styles.dialogBody}>
        <p>Sau khi hoàn tất, phiên bản hiện tại sẽ trở thành biên bản chính thức của cuộc họp.</p>
      </div>
      <div className={styles.dialogActions}>
        <button type="button" className={styles.secondaryButton} onClick={onClose}>
          Hủy
        </button>
        <button type="button" className={styles.primaryButton} onClick={onConfirm}>
          Xác nhận hoàn tất
        </button>
      </div>
    </DialogFrame>
  );
}

export function MinutesVersionHistoryDialog({
  versions,
  onClose,
}: {
  versions: MeetingMinutesVersion[];
  onClose: () => void;
}) {
  return (
    <DialogFrame title="Lịch sử phiên bản" onClose={onClose}>
      <div className={styles.dialogBody}>
        <div className={styles.versionList}>
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
      </div>
      <div className={styles.dialogActions}>
        <button type="button" className={styles.primaryButton} onClick={onClose}>
          Đóng
        </button>
      </div>
    </DialogFrame>
  );
}
