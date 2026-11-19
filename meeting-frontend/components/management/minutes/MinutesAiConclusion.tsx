'use client';

import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  generateMeetingConclusion,
  getMeetingConclusion,
} from '@/services/management/managementConclusionService';
import type {
  ConclusionResult,
  ConclusionSource,
  MeetingConclusion,
} from '@/types/management/conclusion';
import styles from '@/styles/management/MinutesAiConclusion.module.css';

function SourceRefs({ refs }: { refs: string[] }) {
  return (
    <small className={styles.sources}>
      Nguồn:{' '}
      {refs
        .map((ref) => {
          if (ref.startsWith('minutes.v')) return `Biên bản V${ref.slice(9)}`;
          if (ref.startsWith('poll.')) return `Biểu quyết #${ref.slice(5)}`;
          if (ref.startsWith('qa.')) return `Hỏi đáp #${ref.slice(3)}`;
          if (ref.startsWith('chat.')) return `Trao đổi #${ref.slice(5)}`;
          return 'Kết luận đã nhập';
        })
        .join(', ')}
    </small>
  );
}

export function ConclusionResultDisplay({ result }: { result: ConclusionResult }) {
  return (
    <div className={styles.result}>
      <div>
        <h5>Tổng hợp</h5>
        <p>{result.summary}</p>
      </div>
      {(
        [
          ['Quyết định', result.decisions],
          ['Kết quả biểu quyết', result.pollConclusions],
          ['Vấn đề chưa thống nhất', result.unresolvedIssues],
        ] as const
      ).map(([title, items]) =>
        items.length ? (
          <div key={title}>
            <h5>{title}</h5>
            <ul>
              {items.map((item, index) => (
                <li key={index}>
                  <p>{item.content}</p>
                  <SourceRefs refs={item.sourceRefs} />
                </li>
              ))}
            </ul>
          </div>
        ) : null,
      )}
      {result.actionItems.length ? (
        <div>
          <h5>Công việc cần thực hiện</h5>
          <ul>
            {result.actionItems.map((item, index) => (
              <li key={index}>
                <p>{item.task}</p>
                <small>
                  Người thực hiện: {item.assignee || 'Chưa được chỉ định'} · Hạn:{' '}
                  {item.deadline || 'Chưa xác định'}
                </small>
                <SourceRefs refs={item.sourceRefs} />
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

function ConfirmConclusion({
  busy,
  onCancel,
  onConfirm,
}: {
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const id = useId();
  const dialog = useRef<HTMLElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    cancel.current?.focus();
    return () => previous?.focus();
  }, []);
  return createPortal(
    <div className={styles.overlay} onMouseDown={() => !busy && onCancel()}>
      <section
        ref={dialog}
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby={id}
        onMouseDown={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key === 'Escape' && !busy) onCancel();
          if (event.key !== 'Tab') return;
          const buttons =
            dialog.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)');
          if (!buttons?.length) {
            event.preventDefault();
            return;
          }
          const first = buttons[0];
          const last = buttons[buttons.length - 1];
          if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
          } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
          }
        }}
      >
        <h3 id={id}>Tạo kết luận bằng AI?</h3>
        <p>
          AI tổng hợp từ biên bản hiện tại, biểu quyết đã đóng, câu hỏi và câu trả lời, nội dung
          trao đổi cùng kết luận bạn đã nhập.
        </p>
        <p>
          Nội dung đã nhập sẽ được giữ lại. Các hiệu chỉnh đang có sẽ được lưu trước khi gửi đến AI.
        </p>
        <p>
          <strong>Mỗi cuộc họp chỉ tạo kết luận bằng AI thành công một lần.</strong> Sau đó bạn vẫn
          có thể hiệu chỉnh thủ công. Nếu xử lý lỗi, bạn được thử lại.
        </p>
        <div className={styles.actions}>
          <button ref={cancel} type="button" disabled={busy} onClick={onCancel}>
            Hủy
          </button>
          <button type="button" className={styles.primary} disabled={busy} onClick={onConfirm}>
            {busy ? 'Đang gửi...' : 'Tạo kết luận'}
          </button>
        </div>
      </section>
    </div>,
    document.body,
  );
}

export function MinutesAiConclusion({
  meetingId,
  source,
  canManage,
  editing = false,
  prepareSource,
  onGenerated,
  onBusyChange,
}: {
  meetingId: string;
  source: ConclusionSource;
  canManage: boolean;
  editing?: boolean;
  prepareSource?: () => Promise<ConclusionSource>;
  onGenerated: () => void;
  onBusyChange: (busy: boolean) => void;
}) {
  const [view, setView] = useState<MeetingConclusion | null>(null);
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const callbacks = useRef({
    onGenerated,
    onBusyChange,
    sourceVersion: source.expectedMinutesVersion,
  });
  callbacks.current = { onGenerated, onBusyChange, sourceVersion: source.expectedMinutesVersion };
  const lastStatus = useRef<MeetingConclusion['status'] | null>(null);
  const mounted = useRef(false);
  const submitting = useRef(false);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      callbacks.current.onBusyChange(false);
    };
  }, []);

  useEffect(() => {
    lastStatus.current = null;
    setView(null);
  }, [meetingId]);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const controller = new AbortController();
    async function load() {
      try {
        const next = await getMeetingConclusion(meetingId, controller.signal);
        if (controller.signal.aborted) return;
        const completed =
          next.status === 'GENERATED' &&
          (lastStatus.current === 'GENERATING' ||
            (next.currentMinutesVersion ?? 0) > callbacks.current.sourceVersion);
        lastStatus.current = next.status;
        setView(next);
        if (next.status === 'GENERATED') setError('');
        if (completed) callbacks.current.onGenerated();
        if (next.status === 'GENERATING') timer = setTimeout(load, 2000);
      } catch (failure) {
        if (controller.signal.aborted) return;
        setError(failure instanceof Error ? failure.message : 'Không thể tải trạng thái kết luận.');
        if (lastStatus.current === 'GENERATING') timer = setTimeout(load, 5000);
      }
    }
    void load();
    return () => {
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [meetingId, refresh]);

  const busy = pending || view?.status === 'GENERATING';
  useEffect(() => {
    callbacks.current.onBusyChange(busy);
  }, [busy]);

  async function generate() {
    if (submitting.current || !view?.canGenerate || !canManage) return;
    submitting.current = true;
    setPending(true);
    setError('');
    try {
      const current = prepareSource ? await prepareSource() : source;
      const next = await generateMeetingConclusion(meetingId, current);
      if (!mounted.current) return;
      lastStatus.current = next.status;
      setView(next);
      setConfirm(false);
      setRefresh((value) => value + 1);
    } catch (failure) {
      if (!mounted.current) return;
      setError(failure instanceof Error ? failure.message : 'Không thể tạo kết luận.');
      setConfirm(false);
      // A lost HTTP response may still have created a durable job on the server.
      setRefresh((value) => value + 1);
    } finally {
      submitting.current = false;
      if (mounted.current) setPending(false);
    }
  }

  return (
    <div className={styles.panel} aria-label="Kết luận bằng AI">
      {!editing ? (
        <p className={styles.finalText}>{source.manualContent || 'Chưa có kết luận.'}</p>
      ) : null}
      <div className={styles.toolbar}>
        {view?.status === 'GENERATED' ? (
          <span className={styles.badge}>Đã tạo kết luận bằng AI</span>
        ) : (
          <span>AI hỗ trợ tổng hợp kết luận</span>
        )}
        {canManage && view?.canManage ? (
          <button
            type="button"
            className={styles.primary}
            disabled={
              busy ||
              !view.canGenerate ||
              view.currentMinutesVersion !== source.expectedMinutesVersion
            }
            onClick={() => setConfirm(true)}
          >
            {busy
              ? 'Đang tổng hợp...'
              : view.status === 'GENERATED'
                ? 'Đã sử dụng AI'
                : view.status === 'FAILED'
                  ? 'Thử lại bằng AI'
                  : 'Kết luận bằng AI'}
          </button>
        ) : null}
      </div>
      <div aria-live="polite">
        {busy ? (
          <p className={styles.note}>
            Đang tổng hợp kết luận. Bạn có thể rời trang và quay lại để xem kết quả.
          </p>
        ) : null}
        {view?.status === 'FAILED' ? (
          <p role="alert" className={styles.error}>
            {view.errorMessage}
          </p>
        ) : null}
        {view?.status === 'NOT_GENERATED' && canManage && !view.canGenerate ? (
          <p className={styles.note}>
            Kết luận bằng AI sẵn sàng khi cuộc họp đã kết thúc và có biên bản.
          </p>
        ) : null}
        {view?.status === 'GENERATED' ? (
          <p className={styles.note}>
            {canManage && view.canManage
              ? 'Bạn vẫn có thể hiệu chỉnh kết luận. AI không được tạo lại cho cuộc họp này.'
              : 'Kết luận AI đã được lưu. Chỉ người có quyền quản lý biên bản mới được hiệu chỉnh.'}
          </p>
        ) : null}
        {error ? (
          <p role="alert" className={styles.error}>
            {error}{' '}
            <button
              type="button"
              disabled={pending}
              onClick={() => {
                setError('');
                setRefresh((value) => value + 1);
              }}
            >
              Tải lại trạng thái
            </button>
          </p>
        ) : null}
      </div>
      {view?.status === 'GENERATED' && view.aiGeneratedContent ? (
        <details className={styles.provenance}>
          <summary>Xem nội dung đã nhập và đề xuất gốc của AI</summary>
          {view.manualContentBeforeAi?.trim() ? (
            <div className={styles.manual}>
              <span className={styles.badge}>Nội dung đã nhập</span>
              <p>{view.manualContentBeforeAi}</p>
            </div>
          ) : null}
          <div className={styles.ai}>
            <span className={styles.badge}>AI đề xuất</span>
            <ConclusionResultDisplay result={view.aiGeneratedContent} />
          </div>
        </details>
      ) : null}
      {confirm ? (
        <ConfirmConclusion
          busy={pending}
          onCancel={() => setConfirm(false)}
          onConfirm={() => void generate()}
        />
      ) : null}
    </div>
  );
}
