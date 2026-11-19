'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import { MeetingPollEditor } from './MeetingPollEditor';
import { MeetingPollBallot } from './MeetingPollBallot';
import { MeetingPollResults } from './MeetingPollResults';
import { MeetingPollHistory } from './MeetingPollHistory';
import { ManagementApiError } from '@/services/management/httpClient';
import {
  PollRequestCoordinator,
  withPollDeadline,
} from '@/services/management/pollRequestCoordinator';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import {
  createPoll,
  getActivePoll,
  getPoll,
  getPollContext,
  getPolls,
  transitionPoll,
  updatePoll,
  submitPollVote,
} from '@/services/management/managementPollService';
import type { MeetingPoll, PollContext, PollDraftInput, PollPage } from '@/types/management/poll';
import { POLL_STATUS_LABELS } from '@/types/management/poll';
import styles from '@/styles/management/Polls.module.css';

interface Props {
  meetingId: string;
  participantToken?: string;
  onClose?: () => void;
}

export function MeetingPollPanel({ meetingId, participantToken, onClose }: Props) {
  const { authenticated, loading: authLoading, user } = useManagementAuth();
  const [context, setContext] = useState<PollContext | null>(null);
  const [history, setHistory] = useState<PollPage | null>(null);
  const [active, setActive] = useState<MeetingPoll | null>(null);
  const [page, setPage] = useState(0);
  const [editor, setEditor] = useState<MeetingPoll | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const coordinator = useRef(new PollRequestCoordinator());
  const lastPollId = useRef<number | null>(null);
  const busyRef = useRef(false);
  const lifetime = useRef({ revision: 0 });
  const writeController = useRef<AbortController | null>(null);

  const refresh = useCallback(
    async (full = true) => {
      if (busyRef.current) return;
      await coordinator.current.run(
        async (signal) => {
          const [nextContext, nextHistory, nextActive] = await Promise.all([
            full ? getPollContext(meetingId, signal, participantToken) : Promise.resolve(null),
            full ? getPolls(meetingId, page, signal, participantToken) : Promise.resolve(null),
            getActivePoll(meetingId, signal, participantToken),
          ]);
          const previousId = lastPollId.current;
          const displayed =
            nextActive ??
            (previousId == null
              ? null
              : (nextHistory?.content.find((poll) => poll.id === previousId) ??
                (await getPoll(meetingId, previousId, signal, participantToken))));
          return { nextContext, nextHistory, nextActive, displayed };
        },
        ({ nextContext, nextHistory, nextActive, displayed }) => {
          if (nextActive) lastPollId.current = nextActive.id;
          if (nextContext) setContext(nextContext);
          if (nextHistory) setHistory(nextHistory);
          setActive(displayed);
          setError(null);
          setLoading(false);
        },
        (failure) => {
          if (failure instanceof ManagementApiError && [401, 403, 404].includes(failure.status)) {
            setActive(null);
            setHistory(null);
            setContext(null);
            setEditor(undefined);
            lastPollId.current = null;
          }
          setError(failure instanceof Error ? failure.message : 'Không thể tải biểu quyết.');
          setLoading(false);
        },
      );
    },
    [meetingId, page, participantToken],
  );

  useEffect(() => {
    const currentLifetime = lifetime.current;
    const requests = coordinator.current;
    currentLifetime.revision++;
    lastPollId.current = null;
    busyRef.current = false;
    setBusy(false);
    setActive(null);
    setHistory(null);
    setContext(null);
    setEditor(undefined);
    setActionError(null);
    setLoading(authenticated || !!participantToken);
    return () => {
      currentLifetime.revision++;
      requests.invalidate();
      writeController.current?.abort();
    };
  }, [meetingId, authenticated, user?.publicId, participantToken]);

  useEffect(() => {
    if (!authenticated && !participantToken) return;
    const requests = coordinator.current;
    void refresh();
    const wake = () => {
      if (document.visibilityState === 'visible') void refresh();
    };
    let ticks = 0;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible' && !coordinator.current.pending)
        void refresh(++ticks % 5 === 0);
    }, 3000);
    window.addEventListener('focus', wake);
    window.addEventListener('online', wake);
    document.addEventListener('visibilitychange', wake);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('focus', wake);
      window.removeEventListener('online', wake);
      document.removeEventListener('visibilitychange', wake);
      requests.invalidate();
    };
  }, [authenticated, participantToken, refresh, user?.publicId]);

  const editable =
    context?.meetingStatus === 'UPCOMING' || context?.meetingStatus === 'IN_PROGRESS';
  const live = context?.meetingStatus === 'IN_PROGRESS';

  async function act(operation: (signal: AbortSignal) => Promise<MeetingPoll>, message: string) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    coordinator.current.invalidate();
    const revision = lifetime.current.revision;
    const controller = new AbortController();
    writeController.current = controller;
    try {
      const confirmed = await withPollDeadline(operation, controller);
      if (revision !== lifetime.current.revision) return;
      if (confirmed.status !== 'DRAFT') {
        lastPollId.current = confirmed.id;
        setActive(confirmed);
      }
      setEditor(undefined);
      setActionError(null);
      toast.success(message);
    } catch (failure) {
      if (revision !== lifetime.current.revision) return;
      setActionError(failure instanceof Error ? failure.message : 'Không thể thực hiện thao tác.');
      toast.error(failure instanceof Error ? failure.message : 'Không thể thực hiện thao tác.');
    } finally {
      if (revision === lifetime.current.revision) {
        busyRef.current = false;
        setBusy(false);
        writeController.current = null;
        await refresh();
      }
    }
  }

  async function save(input: PollDraftInput) {
    await act(
      (signal) =>
        editor
          ? updatePoll(meetingId, editor.id, input, signal, participantToken)
          : createPoll(meetingId, input, signal, participantToken),
      'Đã lưu bản nháp.',
    );
  }

  return (
    <section className={styles.panel} aria-label="Biểu quyết">
      <div className={styles.heading}>
        <h3>Biểu quyết</h3>
        {onClose ? (
          <button
            type="button"
            className={styles.iconControl}
            onClick={onClose}
            aria-label="Đóng bảng biểu quyết"
            title="Đóng bảng biểu quyết"
          >
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              aria-hidden="true"
              focusable="false"
            >
              <path d="m6 6 12 12M18 6 6 18" />
            </svg>
          </button>
        ) : null}
      </div>
      {(authLoading && !participantToken) || loading ? (
        <p>Đang tải biểu quyết...</p>
      ) : !authenticated && !participantToken ? (
        <p className={styles.hint}>Vào phòng họp để sử dụng biểu quyết.</p>
      ) : (
        <>
          {error || actionError ? (
            <div className={styles.error} role="alert">
              <p>{actionError ?? error}</p>
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  setActionError(null);
                  void refresh();
                }}
              >
                Tải lại
              </button>
            </div>
          ) : null}
          {editor !== undefined && context?.canManage && editable ? (
            <MeetingPollEditor
              key={editor?.id ?? 'new'}
              initial={editor}
              context={context}
              busy={busy || !!error || !!actionError}
              onSave={save}
              onCancel={() => setEditor(undefined)}
            />
          ) : (
            <>
              {context?.canManage && editable ? (
                <button
                  type="button"
                  className={styles.primary}
                  disabled={busy || !!error || !!actionError}
                  onClick={() => setEditor(null)}
                >
                  + Tạo biểu quyết
                </button>
              ) : null}
              {active ? (
                <article className={styles.card}>
                  <span className={active.status === 'OPEN' ? styles.openBadge : styles.status}>
                    {POLL_STATUS_LABELS[active.status]}
                  </span>
                  <h4>{active.question}</h4>
                  {active.description ? <p>{active.description}</p> : null}
                  <p className={styles.progress}>
                    {active.votedCount} / {active.eligibleCount} người đã biểu quyết
                  </p>
                  <progress
                    max={Math.max(1, active.eligibleCount)}
                    value={active.votedCount}
                    aria-label="Tiến độ biểu quyết"
                  />
                  <MeetingPollBallot
                    key={`${active.id}:${active.myVote?.updatedAt ?? 'unsent'}`}
                    poll={active}
                    busy={busy || !!error || !!actionError}
                    onVote={(ids) =>
                      act(
                        (signal) =>
                          submitPollVote(meetingId, active.id, ids, signal, participantToken),
                        'Phiếu đã được máy chủ lưu.',
                      )
                    }
                  />
                  <MeetingPollResults poll={active} />
                  {active.canManage && editable && active.status === 'OPEN' ? (
                    <div className={styles.actions}>
                      <button
                        type="button"
                        className={styles.primary}
                        disabled={busy || !!error || !!actionError}
                        onClick={() =>
                          void act(
                            (signal) =>
                              transitionPoll(
                                meetingId,
                                active.id,
                                'close',
                                signal,
                                participantToken,
                              ),
                            'Đã đóng biểu quyết.',
                          )
                        }
                      >
                        Đóng biểu quyết
                      </button>
                      <button
                        type="button"
                        className={styles.danger}
                        disabled={busy || !!error || !!actionError}
                        onClick={() => {
                          if (window.confirm('Hủy biểu quyết này? Các phiếu đã lưu được giữ lại.'))
                            void act(
                              (signal) =>
                                transitionPoll(
                                  meetingId,
                                  active.id,
                                  'cancel',
                                  signal,
                                  participantToken,
                                ),
                              'Đã hủy biểu quyết.',
                            );
                        }}
                      >
                        Hủy
                      </button>
                    </div>
                  ) : null}
                </article>
              ) : (
                <p className={styles.empty}>Hiện chưa có biểu quyết đang mở.</p>
              )}
              {history ? (
                <div className={styles.history}>
                  <h4>Nội dung biểu quyết ({history?.totalElements ?? 0})</h4>
                  <MeetingPollHistory
                    polls={history.content.filter((poll) => poll.id !== active?.id)}
                    editable={editable}
                    live={live}
                    hasOpenPoll={active?.status === 'OPEN'}
                    busy={busy || !!error || !!actionError}
                    onEdit={setEditor}
                    onOpen={(poll) =>
                      void act(
                        (signal) =>
                          transitionPoll(meetingId, poll.id, 'open', signal, participantToken),
                        'Đã mở biểu quyết.',
                      )
                    }
                    onCancel={(poll) => {
                      if (window.confirm('Hủy bản nháp này?'))
                        void act(
                          (signal) =>
                            transitionPoll(meetingId, poll.id, 'cancel', signal, participantToken),
                          'Đã hủy bản nháp.',
                        );
                    }}
                  />
                  {history && history.totalPages > 1 ? (
                    <div className={styles.actions}>
                      <button
                        type="button"
                        className={styles.secondary}
                        disabled={busy || page === 0}
                        onClick={() => setPage((current) => current - 1)}
                      >
                        Trước
                      </button>
                      <span>
                        {page + 1} / {history.totalPages}
                      </span>
                      <button
                        type="button"
                        className={styles.secondary}
                        disabled={busy || history.last}
                        onClick={() => setPage((current) => current + 1)}
                      >
                        Sau
                      </button>
                    </div>
                  ) : null}
                </div>
              ) : null}
            </>
          )}
        </>
      )}
    </section>
  );
}
