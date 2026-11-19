'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import { ManagementApiError } from '@/services/management/httpClient';
import {
  ManagementRequestCoordinator,
  withManagementDeadline,
} from '@/services/management/managementRequestCoordinator';
import { QnaRefreshScheduler } from '@/services/management/qnaRefreshScheduler';
import {
  actOnSpeakingRequest,
  createSpeakingRequest,
  getSpeakingContext,
  getSpeakingRequests,
} from '@/services/management/managementSpeakingService';
import {
  readSpeakingDraft,
  newSpeakingRequestId,
  speakingDraftKey,
  storeSpeakingDraft,
  type SpeakingDraft,
} from '@/services/management/speakingDraft';
import {
  type SpeakingAction,
  type SpeakingContext,
  type SpeakingPage,
  type SpeakingRequest,
} from '@/types/management/speaking';
import { SpeakingIcon } from './SpeakingIcon';
import { SpeakingTimeline } from './SpeakingTimeline';
import styles from '@/styles/management/Speaking.module.css';

interface Props {
  meetingId: string;
  attendeeId: string;
  participantToken?: string;
  refreshKey?: number;
  onChanged?: () => void;
  onClose?: () => void;
}

export function MeetingSpeakingPanel(props: Props) {
  const { authenticated, user } = useManagementAuth();
  const key = JSON.stringify([
    props.meetingId,
    props.attendeeId,
    props.participantToken ?? '',
    authenticated,
    user?.publicId ?? '',
  ]);
  return <SpeakingSession key={key} {...props} />;
}

function SpeakingSession({
  meetingId,
  attendeeId,
  participantToken,
  refreshKey,
  onChanged,
  onClose,
}: Props) {
  const storageKey = speakingDraftKey(meetingId, attendeeId);
  const [context, setContext] = useState<SpeakingContext | null>(null);
  const [result, setResult] = useState<{ page: number; data: SpeakingPage } | null>(null);
  const [page, setPage] = useState(0);
  const [speakerName, setSpeakerName] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const retry = useRef<SpeakingDraft | null>(null);
  const coordinator = useRef(new ManagementRequestCoordinator());
  const scheduler = useRef<QnaRefreshScheduler | null>(null);
  const writeController = useRef<AbortController | null>(null);
  const busyRef = useRef(false);
  const lifetime = useRef({ active: true });
  const contextRef = useRef(context);
  contextRef.current = context;
  const changed = useRef(onChanged);
  changed.current = onChanged;
  const displayed = result?.page === page ? result.data : null;

  const clearAccess = useCallback(() => {
    setContext(null);
    setResult(null);
    setSpeakerName('');
    setNotice(null);
    setActionError(null);
  }, []);

  const refresh = useCallback(async () => {
    if (busyRef.current) return;
    setLoading(true);
    await coordinator.current.run(
      async (signal) => {
        const [nextContext, nextPage] = await Promise.all([
          getSpeakingContext(meetingId, participantToken, signal),
          getSpeakingRequests(meetingId, page, participantToken, signal),
        ]);
        return { nextContext, nextPage };
      },
      ({ nextContext, nextPage }) => {
        setContext(nextContext);
        setResult({ page, data: nextPage });
        setError(null);
        setLoading(false);
        if (page > 0 && page >= nextPage.totalPages) setPage(Math.max(0, nextPage.totalPages - 1));
        const draft = retry.current;
        if (
          draft &&
          nextPage.content.some(
            (row) =>
              row.requestId === draft.requestId && row.isOwn && row.pointId === draft.pointId,
          )
        ) {
          retry.current = null;
          storeSpeakingDraft(storageKey, null);
          setSpeakerName((value) => (value.trim() === draft.speakerName ? '' : value));
          setActionError(null);
          setNotice('Máy chủ đã xác nhận yêu cầu phát biểu của bạn.');
        }
      },
      (failure) => {
        if (failure instanceof ManagementApiError && [401, 403, 404].includes(failure.status))
          clearAccess();
        setError(failure instanceof Error ? failure.message : 'Không thể tải lịch sử phát biểu.');
        setLoading(false);
      },
    );
  }, [meetingId, participantToken, page, storageKey, clearAccess]);
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;

  useEffect(() => {
    const session = lifetime.current;
    const reads = coordinator.current;
    session.active = true;
    const draft = readSpeakingDraft(storageKey);
    retry.current = draft;
    if (draft) setSpeakerName(draft.speakerName);
    const engine = new QnaRefreshScheduler(
      () => refreshRef.current(),
      () => document.visibilityState === 'visible' && !busyRef.current,
    );
    scheduler.current = engine;
    const wake = () => engine.request();
    const visible = () => {
      if (document.visibilityState === 'visible') engine.request();
    };
    const timer = window.setInterval(() => {
      if (!contextRef.current?.readOnly) engine.poll();
    }, 5000);
    window.addEventListener('focus', wake);
    window.addEventListener('online', wake);
    document.addEventListener('visibilitychange', visible);
    return () => {
      session.active = false;
      engine.dispose();
      scheduler.current = null;
      reads.invalidate();
      writeController.current?.abort();
      window.clearInterval(timer);
      window.removeEventListener('focus', wake);
      window.removeEventListener('online', wake);
      document.removeEventListener('visibilitychange', visible);
    };
  }, [storageKey]);

  useEffect(() => {
    const reads = coordinator.current;
    reads.invalidate();
    scheduler.current?.request();
    return () => reads.invalidate();
  }, [refresh, refreshKey]);

  async function mutate(
    operation: (signal: AbortSignal) => Promise<SpeakingRequest>,
    saved: string,
    submitting = false,
  ) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setNotice(null);
    setActionError(null);
    coordinator.current.invalidate();
    const controller = new AbortController();
    writeController.current = controller;
    try {
      await withManagementDeadline(operation, controller);
      if (!lifetime.current.active) return;
      if (submitting) {
        retry.current = null;
        storeSpeakingDraft(storageKey, null);
        setSpeakerName('');
        setPage(0);
      }
      setNotice(saved);
      try {
        changed.current?.();
      } catch {
        /* HTTP confirmation is authoritative. */
      }
    } catch (failure) {
      if (!lifetime.current.active) return;
      if (failure instanceof ManagementApiError && [401, 403, 404].includes(failure.status))
        clearAccess();
      setActionError(
        failure instanceof Error ? failure.message : 'Máy chủ chưa xác nhận thao tác.',
      );
    } finally {
      if (lifetime.current.active) {
        busyRef.current = false;
        setBusy(false);
        writeController.current = null;
        scheduler.current?.request();
      }
    }
  }

  function submit() {
    if (busyRef.current || !context?.canRequest || !context.pointId) return;
    const normalized = speakerName.trim();
    if (normalized.length > 255) return;
    let draft = retry.current;
    if (!draft || draft.speakerName !== normalized || draft.pointId !== context.pointId) {
      try {
        draft = {
          requestId: newSpeakingRequestId(),
          pointId: context.pointId,
          speakerName: normalized,
        };
      } catch (failure) {
        setActionError(
          failure instanceof Error ? failure.message : 'Không thể chuẩn bị yêu cầu phát biểu.',
        );
        return;
      }
      retry.current = draft;
      storeSpeakingDraft(storageKey, draft);
    }
    const input = { requestId: draft.requestId, speakerName: draft.speakerName };
    void mutate(
      (signal) => createSpeakingRequest(meetingId, input, participantToken, signal),
      'Đã gửi yêu cầu phát biểu.',
      true,
    );
  }

  function action(row: SpeakingRequest, name: SpeakingAction) {
    void mutate(
      (signal) =>
        actOnSpeakingRequest(meetingId, row.id, name, row.version, participantToken, signal),
      name === 'approve'
        ? 'Đã duyệt lượt phát biểu.'
        : name === 'finish'
          ? 'Đã kết thúc lượt phát biểu.'
          : 'Đã hủy yêu cầu.',
    );
  }

  return (
    <section className={styles.panel} aria-label="Lịch sử phát biểu" aria-busy={busy}>
      <header className={styles.header}>
        <h2>Lịch sử phát biểu</h2>
        <div className={styles.tools}>
          <button
            type="button"
            aria-label="Làm mới lịch sử phát biểu"
            title="Làm mới"
            disabled={busy || loading}
            onClick={() => scheduler.current?.request()}
          >
            <SpeakingIcon name="refresh" />
          </button>
          {onClose ? (
            <button
              type="button"
              aria-label="Đóng lịch sử phát biểu"
              title="Đóng"
              onClick={onClose}
            >
              <SpeakingIcon name="close" />
            </button>
          ) : null}
        </div>
      </header>
      {context?.pointName ? <p className={styles.point}>{context.pointName}</p> : null}
      {context?.readOnly ? (
        <p className={styles.info}>Cuộc họp đã kết thúc. Bạn có thể xem lại lịch sử phát biểu.</p>
      ) : null}
      {error ? (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      ) : null}
      {actionError ? (
        <p role="alert" className={styles.error}>
          {actionError}
        </p>
      ) : null}
      {notice ? (
        <p role="status" className={styles.success}>
          {notice}
        </p>
      ) : null}
      {context?.canRequest && !context.readOnly ? (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <label htmlFor={`speaker-name-${meetingId}`}>
            Tên người phát biểu <span>(máy chung)</span>
          </label>
          <input
            id={`speaker-name-${meetingId}`}
            value={speakerName}
            maxLength={255}
            disabled={busy}
            autoComplete="off"
            placeholder={`Để trống: ${context.displayName || 'tên của bạn'}`}
            aria-describedby={`speaker-help-${meetingId}`}
            onChange={(event) => setSpeakerName(event.target.value)}
          />
          <p id={`speaker-help-${meetingId}`} className={styles.help}>
            Thiết bị cá nhân để trống. Máy chung nhập tên người muốn nói.
          </p>
          <button type="submit" className={styles.primary} disabled={busy}>
            <SpeakingIcon name="hand" />
            {busy ? 'Đang xử lý…' : 'Xin phát biểu'}
          </button>
        </form>
      ) : null}
      <div className={styles.listHeader}>
        <h3>Các lượt phát biểu{displayed ? ` (${displayed.totalElements})` : ''}</h3>
        {loading ? (
          <span role="status" className={styles.help}>
            Đang tải…
          </span>
        ) : null}
      </div>
      {displayed?.content.length === 0 ? (
        <p className={styles.empty}>Chưa có yêu cầu phát biểu.</p>
      ) : null}
      <SpeakingTimeline
        rows={displayed?.content ?? []}
        readOnly={context?.readOnly ?? true}
        busy={busy}
        onAction={action}
      />
      {displayed && displayed.totalPages > 1 ? (
        <nav className={styles.pagination} aria-label="Phân trang lịch sử phát biểu">
          <button
            type="button"
            disabled={page === 0 || busy || loading}
            onClick={() => setPage((value) => value - 1)}
          >
            Trước
          </button>
          <span>
            {page + 1}/{displayed.totalPages}
          </span>
          <button
            type="button"
            disabled={displayed.last || busy || loading}
            onClick={() => setPage((value) => value + 1)}
          >
            Sau
          </button>
        </nav>
      ) : null}
      <p className={styles.footnote}>
        Lịch sử ghi nhận thời điểm xin, duyệt và kết thúc lượt. Microphone được bật/tắt bằng nút
        trong phòng họp.
      </p>
    </section>
  );
}
