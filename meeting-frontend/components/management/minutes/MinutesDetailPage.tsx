'use client';

import { HomeBrandLink } from '@/components/home/HomeBrandLink';

import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import toast from 'react-hot-toast';
import { MeetingDocumentsList } from '@/components/management/documents/MeetingDocumentsList';
import {
  FinalizeMinutesDialog,
  MinutesExportDialog,
  MinutesVersionHistoryDialog,
} from './MinutesDialogs';
import { MINUTES_PROCESSING_UI } from './minutesProcessingLabels';
import { MinutesAiConclusion } from './MinutesAiConclusion';
import type { ConclusionSource } from '@/types/management/conclusion';
import { MinutesEditor } from './MinutesEditor';
import { MinutesStatusBadge } from './MinutesStatusBadge';
import { TranscriptViewer } from './TranscriptViewer';
import {
  contentJsonToEditorContent,
  contentJsonToMinutesContent,
  createMinutesVersion,
  editorContentToContentJson,
  finalizeMinutesVersion,
  generateMinutes,
  getMinutesDetail,
  getMinutesProcessing,
  retryMinutes,
} from '@/services/management/managementMinutesService';
import type { MinutesProcessingApiResponse } from '@/services/management/managementMinutesService';
import type {
  MeetingMinutesListItem,
  MeetingMinutesVersion,
  MinutesDetailMode,
  MinutesDetailTab,
  MinutesStatus,
} from '@/types/management/minutes';
import styles from '@/styles/management/Minutes.module.css';

function formatDate(value: string) {
  return new Intl.DateTimeFormat('vi-VN').format(new Date(`${value}T00:00:00`));
}

function formatDateTime(value?: string) {
  if (!value) return '-';
  return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short', timeStyle: 'short' }).format(
    new Date(value),
  );
}

function versionLabel(version: MeetingMinutesVersion) {
  if (version.source === 'SYSTEM_GENERATED') return `Version ${version.version}`;
  if (version.source === 'AI_GENERATED') return `Version ${version.version} (AI)`;
  if (version.source === 'SECRETARY_EDITED') return `Version ${version.version} (Thư ký)`;
  if (version.source === 'OWNER_EDITED') return `Version ${version.version} (Người tạo)`;
  return `Version ${version.version} (Chủ tọa)`;
}

function roleLabel(role: string) {
  if (role === 'CHAIRMAN') return 'Chủ tọa';
  if (role === 'SECRETARY') return 'Thư ký';
  return 'Thành viên';
}

type DetailGlyphName =
  | 'back'
  | 'calendar'
  | 'check'
  | 'clock'
  | 'document'
  | 'download'
  | 'edit'
  | 'history'
  | 'info'
  | 'lightbulb'
  | 'microphone'
  | 'participants'
  | 'tag'
  | 'user';

function DetailGlyph({ name }: { name: DetailGlyphName }) {
  const paths: Record<DetailGlyphName, ReactNode> = {
    back: <path d="m15 18-6-6 6-6" />,
    calendar: (
      <>
        <path d="M7 3v3M17 3v3M4 9h16" />
        <rect x="4" y="5" width="16" height="15" rx="2" />
      </>
    ),
    check: (
      <>
        <circle cx="12" cy="12" r="8" />
        <path d="m8.5 12.2 2.2 2.2 4.8-5" />
      </>
    ),
    clock: (
      <>
        <circle cx="12" cy="12" r="8" />
        <path d="M12 8v4l3 2" />
      </>
    ),
    document: (
      <>
        <path d="M7 3.5h6l4 4v13H7z" />
        <path d="M13 3.5v4h4M9.5 12h5M9.5 15h5" />
      </>
    ),
    download: (
      <>
        <path d="M12 4v10" />
        <path d="m8 10 4 4 4-4" />
        <path d="M5 20h14" />
      </>
    ),
    edit: (
      <>
        <path d="M4 20h4l10.5-10.5a2.1 2.1 0 0 0-3-3L5 17z" />
        <path d="m14 7 3 3" />
      </>
    ),
    history: (
      <>
        <path d="M4 12a8 8 0 1 0 2.35-5.65" />
        <path d="M4 5.5v4h4M12 8v5l3 2" />
      </>
    ),
    info: (
      <>
        <circle cx="12" cy="12" r="8" />
        <path d="M12 11v5M12 8h.01" />
      </>
    ),
    lightbulb: (
      <>
        <path d="M9 18h6M10 21h4" />
        <path d="M8 13.5a6 6 0 1 1 8 0c-.8.7-1.1 1.5-1.1 2.5H9.1c0-1-.3-1.8-1.1-2.5Z" />
      </>
    ),
    microphone: (
      <>
        <rect x="9" y="3" width="6" height="11" rx="3" />
        <path d="M5 11a7 7 0 0 0 14 0M12 18v3" />
      </>
    ),
    participants: (
      <>
        <path d="M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM17 11a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z" />
        <path d="M3.5 19a5.5 5.5 0 0 1 11 0M14.5 18a4 4 0 0 1 6 0" />
      </>
    ),
    tag: (
      <>
        <path d="M4 11V5h6l9 9-6 6z" />
        <path d="M8 8h.01" />
      </>
    ),
    user: (
      <>
        <circle cx="12" cy="8" r="3.5" />
        <path d="M5 20a7 7 0 0 1 14 0" />
      </>
    ),
  };

  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      {paths[name]}
    </svg>
  );
}

interface MinutesDetailPageProps {
  meetingId: string;
  initialTab?: string;
  initialSection?: string;
}

export function MinutesDetailPage({
  meetingId,
  initialTab,
  initialSection,
}: MinutesDetailPageProps) {
  const router = useRouter();
  const [meeting, setMeeting] = useState<MeetingMinutesListItem | null>(null);
  const [versions, setVersions] = useState<MeetingMinutesVersion[]>([]);
  const [selectedVersionNumber, setSelectedVersionNumber] = useState(1);
  const [mode, setMode] = useState<MinutesDetailMode>('VIEW');
  const [activeTab, setActiveTab] = useState<MinutesDetailTab>(
    initialTab === 'details' ? 'DETAILS' : 'MINUTES',
  );
  const [status, setStatus] = useState<MinutesStatus>('NOT_GENERATED');
  const [draftMainContent, setDraftMainContent] = useState('');
  const [draftConclusion, setDraftConclusion] = useState('');
  const [saveVersionLoading, setSaveVersionLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [generateLoading, setGenerateLoading] = useState(false);
  const [retryLoading, setRetryLoading] = useState(false);
  const [processing, setProcessing] = useState<MinutesProcessingApiResponse | null>(null);
  const [showFinalize, setShowFinalize] = useState(false);
  const [showExport, setShowExport] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [conclusionBusy, setConclusionBusy] = useState(false);

  const selectedVersion = useMemo(
    () => versions.find((version) => version.version === selectedVersionNumber) ?? versions[0],
    [selectedVersionNumber, versions],
  );
  const selectedEditorContent = useMemo(
    () => contentJsonToEditorContent(selectedVersion?.contentJson),
    [selectedVersion?.contentJson],
  );
  const latestVersionNumber = Math.max(0, ...versions.map((version) => version.version));
  const hasVersions = versions.length > 0;
  const isNotGenerated = status === 'NOT_GENERATED' && !hasVersions;
  const isProcessing = status === 'PROCESSING' && !hasVersions;
  const isFailed = status === 'FAILED' && !hasVersions;
  const isLatestVersion = hasVersions && selectedVersionNumber === latestVersionNumber;
  const nextVersion = latestVersionNumber + 1;
  const canManage = Boolean(meeting?.canManage);

  const loadDetail = useCallback(async () => {
    setLoading(true);
    try {
      const detail = await getMinutesDetail(meetingId);
      const selected = detail.current ?? detail.versions[0] ?? null;
      let nextStatus = selected?.status ?? detail.meeting.status;
      let nextProcessing: MinutesProcessingApiResponse | null = null;

      if (detail.versions.length === 0) {
        try {
          const response = await getMinutesProcessing(meetingId);
          if (response.status === 'QUEUED' || response.status === 'PROCESSING') {
            nextStatus = 'PROCESSING';
            nextProcessing = response;
          } else if (response.status === 'FAILED') {
            nextStatus = 'FAILED';
            nextProcessing = response;
          }
        } catch {
          nextProcessing = null;
        }
      }

      setMeeting({
        ...detail.meeting,
        status: nextStatus,
      });
      setVersions(detail.versions);
      setSelectedVersionNumber(selected?.version ?? 1);
      setStatus(nextStatus);
      setProcessing(nextProcessing);
      const editorContent = contentJsonToEditorContent(selected?.contentJson);
      setDraftMainContent(editorContent.mainContent);
      setDraftConclusion(editorContent.conclusion);
      setMode('VIEW');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Không thể tải biên bản cuộc họp.');
      setMeeting(null);
    } finally {
      setLoading(false);
    }
  }, [meetingId]);

  useEffect(() => {
    void loadDetail();
  }, [loadDetail]);

  useEffect(() => {
    if (status !== 'PROCESSING') return;

    let active = true;
    let intervalId: number | undefined;

    async function loadProcessingStatus() {
      try {
        const response = await getMinutesProcessing(meetingId);
        if (!active) return;

        setProcessing(response);

        if (response.stage === 'DRAFT_READY') {
          active = false;
          if (intervalId) window.clearInterval(intervalId);
          setActiveTab('MINUTES');
          setMode('VIEW');
          await loadDetail();
          return;
        }

        if (response.status === 'COMPLETED') {
          active = false;
          if (intervalId) window.clearInterval(intervalId);
          await loadDetail();
        } else if (response.status === 'FAILED') {
          active = false;
          if (intervalId) window.clearInterval(intervalId);
          setStatus('FAILED');
        }
      } catch (error) {
        console.error('Could not load minutes processing status:', error);
      }
    }

    void loadProcessingStatus();
    intervalId = window.setInterval(() => {
      void loadProcessingStatus();
    }, 2000);

    return () => {
      active = false;
      if (intervalId) window.clearInterval(intervalId);
    };
  }, [loadDetail, meetingId, status]);

  useEffect(() => {
    if (activeTab !== 'DETAILS' || initialSection !== 'documents') return;
    const frame = window.requestAnimationFrame(() => {
      document
        .getElementById('meeting-documents')
        ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [activeTab, initialSection]);

  if (!meeting) {
    return (
      <section className={styles.emptyState}>
        <span aria-hidden="true">□</span>
        <h3>{loading ? 'Đang tải biên bản cuộc họp...' : 'Không tìm thấy biên bản cuộc họp.'}</h3>
        <button
          type="button"
          className={styles.primaryButton}
          onClick={() => router.push('/management/minutes')}
        >
          Quay lại danh sách
        </button>
      </section>
    );
  }
  const resolvedMeeting = meeting;

  function selectVersion(versionNumber: number) {
    const version = versions.find((item) => item.version === versionNumber);
    const editorContent = contentJsonToEditorContent(version?.contentJson);
    setSelectedVersionNumber(versionNumber);
    setDraftMainContent(editorContent.mainContent);
    setDraftConclusion(editorContent.conclusion);
    setMode('VIEW');
  }

  function startEditing() {
    if (!canManage) return;
    if (!selectedVersion || !isLatestVersion) return;
    const editorContent = contentJsonToEditorContent(selectedVersion.contentJson);
    setDraftMainContent(editorContent.mainContent);
    setDraftConclusion(editorContent.conclusion);
    setMode('EDIT');
    setActiveTab('MINUTES');
  }

  const handleGenerateMinutes = async () => {
    if (!canManage || generateLoading) {
      return;
    }

    try {
      setGenerateLoading(true);

      const response = await generateMinutes(meetingId);

      setProcessing(response);
      setStatus('PROCESSING');
      setMeeting((current) =>
        current
          ? {
              ...current,
              status: 'PROCESSING',
            }
          : current,
      );
    } catch (error) {
      console.error('Could not start minutes generation:', error);
    } finally {
      setGenerateLoading(false);
    }
  };

  const handleRetryMinutes = async () => {
    if (!canManage || retryLoading) {
      return;
    }

    try {
      setRetryLoading(true);

      const response = await retryMinutes(meetingId);

      setProcessing(response);
      setStatus('PROCESSING');
      setMeeting((current) =>
        current
          ? {
              ...current,
              status: 'PROCESSING',
            }
          : current,
      );
    } catch (error) {
      console.error('Could not retry minutes generation:', error);
    } finally {
      setRetryLoading(false);
    }
  };

  async function saveEditedVersion(keepEditing = false): Promise<MeetingMinutesVersion | null> {
    if (!canManage || saveVersionLoading || !selectedVersion) return null;

    try {
      setSaveVersionLoading(true);
      const newVersion = await createMinutesVersion(
        resolvedMeeting.meetingId,
        selectedVersion.version,
        editorContentToContentJson(draftMainContent, draftConclusion, selectedVersion.contentJson),
      );
      setVersions((current) => [newVersion, ...current]);
      setSelectedVersionNumber(newVersion.version);
      setStatus(newVersion.status ?? 'DRAFT');
      setProcessing(null);
      setMeeting((current) =>
        current
          ? {
              ...current,
              status: newVersion.status ?? 'DRAFT',
              currentVersion: newVersion.version,
              updatedAt: newVersion.updatedAt,
              content: contentJsonToMinutesContent(newVersion.contentJson),
            }
          : current,
      );
      const editorContent = contentJsonToEditorContent(newVersion.contentJson);
      setDraftMainContent(editorContent.mainContent);
      setDraftConclusion(editorContent.conclusion);
      if (!keepEditing) setMode('VIEW');
      toast.success(`Đã lưu hiệu chỉnh thành Version ${newVersion.version}.`);
      return newVersion;
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Không thể lưu hiệu chỉnh.');
      return null;
    } finally {
      setSaveVersionLoading(false);
    }
  }

  const conclusionSource: ConclusionSource = {
    expectedMinutesVersion: selectedVersion?.version ?? 1,
    manualContent: mode === 'EDIT' ? draftConclusion : selectedEditorContent.conclusion,
    expectedStoredConclusion: selectedEditorContent.conclusion,
  };

  async function prepareConclusionSource(): Promise<ConclusionSource> {
    const changed =
      draftMainContent !== selectedEditorContent.mainContent ||
      draftConclusion !== selectedEditorContent.conclusion;
    if (!changed) return conclusionSource;
    const saved = await saveEditedVersion(true);
    if (!saved) throw new Error('Chưa lưu được hiệu chỉnh. AI chưa được gọi.');
    const content = contentJsonToEditorContent(saved.contentJson);
    return {
      expectedMinutesVersion: saved.version,
      manualContent: content.conclusion,
      expectedStoredConclusion: content.conclusion,
    };
  }

  async function finalizeMinutes() {
    if (!canManage || !selectedVersion) return;
    try {
      const finalizedVersion = await finalizeMinutesVersion(
        resolvedMeeting.meetingId,
        selectedVersion.version,
      );
      setVersions((current) =>
        current.map((version) =>
          version.version === finalizedVersion.version ? finalizedVersion : version,
        ),
      );
      setStatus(finalizedVersion.status ?? 'FINAL');
      setMeeting((current) =>
        current
          ? {
              ...current,
              status: finalizedVersion.status ?? 'FINAL',
              updatedAt: finalizedVersion.updatedAt,
            }
          : current,
      );
      setShowFinalize(false);
      toast.success('Đã hoàn tất biên bản.');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Không thể hoàn tất biên bản.');
    }
  }

  return (
    <div className={styles.detailPage}>
      <section className={styles.detailHeader}>
        <div className={styles.detailHeroOverlay} />
        <div className={styles.detailHeaderMain}>
          <div className={styles.detailBreadcrumb}>
            <span>
              <HomeBrandLink />
            </span>
            <span>/</span>
            <span>Tổng quan</span>
            <span>/</span>
            <span>Biên bản cuộc họp</span>
            <span>/</span>
            <span>{meeting.meetingId}</span>
          </div>
          <button
            type="button"
            className={styles.backTextButton}
            onClick={() => router.push('/management/minutes')}
          >
            <DetailGlyph name="back" />
            <span>Quay lại</span>
          </button>
          <div className={styles.detailTitleRow}>
            <h2>{meeting.title}</h2>
            {mode === 'EDIT' ? (
              <span className={styles.editingBadge}>Đang chỉnh sửa</span>
            ) : (
              <MinutesStatusBadge status={status} />
            )}
          </div>
          <div className={styles.detailMeta}>
            <span>
              <DetailGlyph name="calendar" /> {formatDate(meeting.date)}
            </span>
            <span>
              <DetailGlyph name="clock" /> {meeting.startTime} - {meeting.endTime}
            </span>
            <span>
              <DetailGlyph name="participants" /> {meeting.participantCount} người tham dự
            </span>
            <span>
              <DetailGlyph name="clock" /> ID: {meeting.meetingCode}
            </span>
          </div>
        </div>
        <div className={styles.detailHeroTagline}>
          <span>Kết nối tri thức</span>
          <span>Tạo giá trị mới</span>
          <i aria-hidden="true" />
        </div>
      </section>

      <div className={styles.detailToolbar}>
        <nav className={styles.detailTabs} aria-label="Nội dung biên bản">
          {(
            [
              ['MINUTES', 'Biên bản'],
              // ['TRANSCRIPT', 'Bản ghi lời nói'],
              ['DETAILS', 'Thông tin chi tiết'],
            ] as Array<[MinutesDetailTab, string]>
          ).map(([tab, label]) => (
            <button
              key={tab}
              type="button"
              className={activeTab === tab ? styles.detailTabActive : ''}
              onClick={() => {
                setActiveTab(tab);
                if (tab !== 'MINUTES') setMode('VIEW');
              }}
            >
              {label}
            </button>
          ))}
        </nav>

        {mode === 'VIEW' ? (
          <div className={styles.versionActions}>
            {hasVersions ? (
              <label className={styles.versionSelect}>
                <span className={styles.visuallyHidden}>Phiên bản</span>
                <select
                  value={selectedVersionNumber}
                  onChange={(event) => selectVersion(Number(event.target.value))}
                >
                  {versions.map((version) => (
                    <option key={version.version} value={version.version}>
                      {versionLabel(version)}
                    </option>
                  ))}
                </select>
              </label>
            ) : (
              <span className={styles.versionEmptyText}>Chưa có phiên bản</span>
            )}
            <button
              type="button"
              className={styles.historyButton}
              onClick={() => setShowHistory(true)}
              disabled={!hasVersions}
            >
              <DetailGlyph name="history" />
              <span>Lịch sử phiên bản</span>
            </button>
          </div>
        ) : selectedVersion ? (
          <div className={styles.editVersionMeta}>
            <p>Tạo từ Version {selectedVersion.version}</p>
            <p>Phiên bản sẽ tạo: Version {nextVersion}</p>
          </div>
        ) : null}
      </div>

      {activeTab === 'MINUTES' && mode === 'VIEW' ? (
        <>
          <section className={styles.minutesViewLayout}>
            <article className={styles.minutesDocument}>
              {isNotGenerated ? (
                <div className={styles.minutesStateBlock}>
                  <div className={styles.minutesStateIcon}>✨</div>

                  <h3>Chưa có biên bản</h3>

                  <p>
                    Cuộc họp đã có bản ghi âm và sẵn sàng xử lý. Hệ thống sẽ chuyển giọng nói thành
                    văn bản, kết hợp nội dung trao đổi và chuẩn bị biên bản.
                  </p>

                  {canManage ? (
                    <button
                      type="button"
                      onClick={handleGenerateMinutes}
                      disabled={generateLoading}
                    >
                      {generateLoading ? 'Đang khởi tạo...' : '✨ Tạo biên bản'}
                    </button>
                  ) : null}
                </div>
              ) : isProcessing ? (
                <div className={styles.minutesStateBlock}>
                  <div className={styles.minutesStateIcon}>⏳</div>

                  <h3>Đang tạo biên bản</h3>

                  <p>
                    {processing?.stage
                      ? (MINUTES_PROCESSING_UI[processing.stage]?.processingText ?? 'Đang xử lý')
                      : 'Đang xử lý'}
                  </p>

                  <div
                    className={styles.processingProgress}
                    role="progressbar"
                    aria-valuenow={processing?.progressPercent ?? 0}
                    aria-valuemin={0}
                    aria-valuemax={100}
                  >
                    <span style={{ width: `${processing?.progressPercent ?? 0}%` }} />
                  </div>
                  <div className={styles.processingPercent}>
                    {processing?.progressPercent ?? 0}%
                  </div>
                </div>
              ) : isFailed ? (
                <div className={styles.minutesStateBlock}>
                  <h3>Tạo biên bản không thành công</h3>

                  {processing?.errorMessage ? <p>{processing.errorMessage}</p> : null}

                  {canManage ? (
                    <button type="button" onClick={handleRetryMinutes} disabled={retryLoading}>
                      {retryLoading ? 'Đang thử lại...' : 'Thử lại'}
                    </button>
                  ) : null}
                </div>
              ) : hasVersions ? (
                <>
                  {hasVersions && !isLatestVersion ? (
                    <div className={styles.readOnlyNotice}>
                      Bạn đang xem phiên bản cũ. Phiên bản này chỉ đọc.
                    </div>
                  ) : null}
                  <section>
                    <div className={styles.minutesSectionTitle}>
                      <span className={styles.detailIconTile}>
                        <DetailGlyph name="document" />
                      </span>
                      <h4>NỘI DUNG CHÍNH</h4>
                    </div>
                    <p className={styles.minutesTextBlock}>
                      {selectedEditorContent.mainContent || 'Chưa có nội dung.'}
                    </p>
                  </section>
                  <section>
                    <div className={styles.minutesSectionTitle}>
                      <span className={styles.detailIconTile}>
                        <DetailGlyph name="lightbulb" />
                      </span>
                      <h4>KẾT LUẬN</h4>
                    </div>
                    {isLatestVersion ? (
                      <MinutesAiConclusion
                        meetingId={meetingId}
                        source={conclusionSource}
                        canManage={canManage}
                        onGenerated={() => void loadDetail()}
                        onBusyChange={setConclusionBusy}
                      />
                    ) : (
                      <p className={styles.minutesTextBlock}>
                        {selectedEditorContent.conclusion || 'Chưa có kết luận.'}
                      </p>
                    )}
                  </section>
                </>
              ) : null}
            </article>

            <aside className={styles.infoPanel}>
              <div className={styles.infoPanelHeader}>
                <span className={styles.detailIconTile}>
                  <DetailGlyph name="info" />
                </span>
                <h3>Thông tin cuộc họp</h3>
              </div>
              <dl>
                <div>
                  <span className={styles.infoRowIcon}>
                    <DetailGlyph name="user" />
                  </span>
                  <dt>Chủ tọa</dt>
                  <dd>{meeting.chairmanName}</dd>
                </div>
                <div>
                  <span className={styles.infoRowIcon}>
                    <DetailGlyph name="user" />
                  </span>
                  <dt>Thư ký</dt>
                  <dd>{meeting.secretaryName}</dd>
                </div>
                <div>
                  <span className={styles.infoRowIcon}>
                    <DetailGlyph name="clock" />
                  </span>
                  <dt>Thời lượng</dt>
                  <dd>{meeting.durationLabel}</dd>
                </div>
                <div>
                  <span className={styles.infoRowIcon}>
                    <DetailGlyph name="participants" />
                  </span>
                  <dt>Số người tham dự</dt>
                  <dd>{meeting.participantCount} người</dd>
                </div>
                <div>
                  <span className={styles.infoRowIcon}>
                    <DetailGlyph name="microphone" />
                  </span>
                  <dt>Trạng thái ghi âm</dt>
                  <dd>
                    <span className={styles.recordedDot} />
                    Đã ghi âm ({meeting.recordingSessionCount} phiên)
                  </dd>
                </div>
                <div>
                  <span className={styles.infoRowIcon}>
                    <DetailGlyph name="document" />
                  </span>
                  <dt>Biên bản</dt>
                  <dd>{selectedVersion ? versionLabel(selectedVersion) : 'Chưa có'}</dd>
                </div>
                <div>
                  <span className={styles.infoRowIcon}>
                    <DetailGlyph name="clock" />
                  </span>
                  <dt>Cập nhật lần cuối</dt>
                  <dd>{formatDateTime(selectedVersion?.createdAt ?? meeting.updatedAt)}</dd>
                </div>
              </dl>
            </aside>
          </section>

          {hasVersions ? (
            <div className={styles.viewActions}>
              {canManage ? (
                <>
                  <button
                    type="button"
                    className={`${styles.primaryButton} ${styles.viewActionEdit}`}
                    onClick={startEditing}
                    disabled={!isLatestVersion || conclusionBusy}
                  >
                    <DetailGlyph name="edit" />
                    <span>Hiệu chỉnh</span>
                  </button>
                  <button
                    type="button"
                    className={`${styles.primaryButton} ${styles.viewActionComplete}`}
                    onClick={() => setShowFinalize(true)}
                    disabled={!isLatestVersion || status === 'FINAL' || conclusionBusy}
                  >
                    <DetailGlyph name="check" />
                    <span>{status === 'FINAL' ? 'Đã hoàn tất' : 'Hoàn tất biên bản'}</span>
                  </button>
                </>
              ) : null}
              <button
                type="button"
                className={`${styles.secondaryButton} ${styles.viewActionExport}`}
                onClick={() => setShowExport(true)}
                disabled={!selectedVersion}
              >
                <DetailGlyph name="download" />
                <span>Xuất biên bản</span>
              </button>
            </div>
          ) : null}
        </>
      ) : null}

      {activeTab === 'MINUTES' && mode === 'EDIT' && canManage ? (
        <MinutesEditor
          mainContent={draftMainContent}
          conclusion={draftConclusion}
          versions={versions}
          nextVersion={nextVersion}
          onMainContentChange={setDraftMainContent}
          onConclusionChange={setDraftConclusion}
          onCancel={hasVersions ? () => setMode('VIEW') : undefined}
          onSaveVersion={() => void saveEditedVersion()}
          saving={saveVersionLoading}
          busy={conclusionBusy}
          conclusionAssist={
            selectedVersion && isLatestVersion ? (
              <MinutesAiConclusion
                meetingId={meetingId}
                source={conclusionSource}
                canManage={canManage}
                editing
                prepareSource={prepareConclusionSource}
                onGenerated={() => void loadDetail()}
                onBusyChange={setConclusionBusy}
              />
            ) : null
          }
        />
      ) : null}
      {activeTab === 'TRANSCRIPT' ? (
        <TranscriptViewer entries={meeting.transcript} participants={meeting.participants} />
      ) : null}
      {activeTab === 'DETAILS' ? (
        <section className={styles.detailsPanel}>
          <div className={styles.detailSectionHeader}>
            <span className={styles.detailIconTile}>
              <DetailGlyph name="info" />
            </span>
            <div>
              <h3>Thông tin cuộc họp</h3>
              <p>Các thông tin cơ bản về cuộc họp</p>
            </div>
          </div>
          <div className={styles.detailsGrid}>
            <table className={styles.detailInfoTable}>
              <thead>
                <tr>
                  <th>Thông tin</th>
                  <th>Giá trị</th>
                  <th>Thông tin</th>
                  <th>Giá trị</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <th scope="row">Meeting ID</th>
                  <td>{meeting.meetingCode}</td>
                  <th scope="row">Tên cuộc họp</th>
                  <td>{meeting.title}</td>
                </tr>
                <tr>
                  <th scope="row">Ngày</th>
                  <td>{formatDate(meeting.date)}</td>
                  <th scope="row">Bắt đầu</th>
                  <td>{meeting.startTime}</td>
                </tr>
                <tr>
                  <th scope="row">Kết thúc</th>
                  <td>{meeting.endTime}</td>
                  <th scope="row">Chủ tọa</th>
                  <td>{meeting.chairmanName}</td>
                </tr>
                <tr>
                  <th scope="row">Thư ký</th>
                  <td>{meeting.secretaryName}</td>
                  <th scope="row">Loại cuộc họp</th>
                  <td>{meeting.accessType === 'PRIVATE' ? 'Riêng tư' : 'Công khai'}</td>
                </tr>
                <tr>
                  <th scope="row">Số participant</th>
                  <td>{meeting.participantCount}</td>
                  <th scope="row">Trạng thái cuộc họp</th>
                  <td>
                    <span className={styles.detailMeetingStatusBadge}>Đã kết thúc</span>
                  </td>
                </tr>
                <tr>
                  <th scope="row">Trạng thái biên bản</th>
                  <td>
                    <MinutesStatusBadge status={status} />
                  </td>
                  <th scope="row" />
                  <td />
                </tr>
              </tbody>
            </table>
          </div>
          <section className={styles.detailSubsection}>
            <div className={styles.detailSectionHeader}>
              <span className={styles.detailIconTile}>
                <DetailGlyph name="participants" />
              </span>
              <div>
                <h3>Danh sách người tham dự</h3>
                <p>Những người tham gia cuộc họp</p>
              </div>
              <span className={styles.participantCountBadge}>
                <DetailGlyph name="participants" />
                {meeting.participantCount} người tham dự
              </span>
            </div>
            <div className={styles.participantTableScroller}>
              <table className={styles.participantDetailTable}>
                <thead>
                  <tr>
                    <th>Người tham dự</th>
                    <th>Vai trò</th>
                    <th>Mã người dùng</th>
                  </tr>
                </thead>
                <tbody>
                  {meeting.participants.map((participant) => (
                    <tr key={participant.id}>
                      <td data-label="Người tham dự">
                        <span className={styles.participantTablePerson}>
                          <span className={styles.participantAvatar}>{participant.initials}</span>
                          <strong>{participant.name}</strong>
                        </span>
                      </td>
                      <td data-label="Vai trò">
                        <span
                          className={`${styles.participantRoleBadge} ${styles[`participantRole${participant.role}`]}`}
                        >
                          {roleLabel(participant.role)}
                        </span>
                      </td>
                      <td
                        data-label="Mã người dùng"
                        className={styles.participantUserId}
                        title={participant.id}
                      >
                        {participant.id}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
          <section id="meeting-documents" className={styles.documentsSection}>
            <div className={styles.detailSectionHeader}>
              <span className={styles.detailIconTile}>
                <DetailGlyph name="document" />
              </span>
              <div>
                <h3>Tài liệu cuộc họp</h3>
                <p>Các tài liệu được chia sẻ trong cuộc họp</p>
              </div>
            </div>
            <MeetingDocumentsList documents={meeting.documents} variant="detail" />
          </section>
        </section>
      ) : null}

      {showFinalize && canManage ? (
        <FinalizeMinutesDialog onClose={() => setShowFinalize(false)} onConfirm={finalizeMinutes} />
      ) : null}
      {showExport && selectedVersion ? (
        <MinutesExportDialog
          meetingId={resolvedMeeting.meetingId}
          version={selectedVersion}
          onClose={() => setShowExport(false)}
        />
      ) : null}
      {showHistory ? (
        <MinutesVersionHistoryDialog versions={versions} onClose={() => setShowHistory(false)} />
      ) : null}
    </div>
  );
}
