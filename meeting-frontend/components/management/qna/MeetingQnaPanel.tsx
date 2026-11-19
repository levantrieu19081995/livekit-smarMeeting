'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import { ManagementApiError } from '@/services/management/httpClient';
import { qnaErrorMessage } from '@/services/management/qnaErrorMessage';
import { QnaRefreshScheduler } from '@/services/management/qnaRefreshScheduler';
import { saveQnaReportFile } from '@/services/management/qnaReportDownload';
import {
  ManagementRequestCoordinator,
  withManagementDeadline,
} from '@/services/management/managementRequestCoordinator';
import {
  askQnaQuestion,
  getQnaContext,
  getQnaQuestions,
  getQnaReport,
  moderateQnaQuestion,
  setQnaUpvote,
  updateQnaSettings,
} from '@/services/management/managementQnaService';
import type {
  QnaContext,
  QnaFilter,
  QnaModerationAction,
  QnaPage,
  QnaQuestion,
  QnaSettingsInput,
  QnaSort,
} from '@/types/management/qna';
import { QnaQuestionList } from './QnaQuestionList';
import { QnaSettings } from './QnaSettings';
import { QnaDeleteDialog } from './QnaDeleteDialog';
import { QnaIcon } from './QnaIcon';
import styles from '@/styles/management/Qna.module.css';

interface Props {
  meetingId: string;
  participantToken?: string;
  onClose?: () => void;
  refreshKey?: number;
  onChanged?: () => void;
  historyHref?: string;
}

// A different meeting/token/account mounts a clean session before any render can
// expose the previous participant's questions, draft or error.
export function MeetingQnaPanel(props: Props) {
  const { authenticated, user } = useManagementAuth();
  const sessionKey = JSON.stringify([
    props.meetingId,
    props.participantToken ?? '',
    authenticated,
    user?.publicId ?? '',
  ]);
  return <QnaSession key={sessionKey} {...props} />;
}

function QnaSession({
  meetingId,
  participantToken,
  onClose,
  refreshKey,
  onChanged,
  historyHref,
}: Props) {
  const [context, setContext] = useState<QnaContext | null>(null);
  const [reportHasQuestions, setReportHasQuestions] = useState(false);
  const [result, setResult] = useState<{ queryKey: string; data: QnaPage } | null>(null);
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<QnaSort>('NEWEST');
  const [filter, setFilter] = useState<QnaFilter>('ALL');
  const [content, setContent] = useState('');
  const [anonymous, setAnonymous] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<QnaQuestion | null>(null);
  const retry = useRef<{ fingerprint: string; clientQuestionId: string } | null>(null);
  const requests = useRef(new ManagementRequestCoordinator());
  const writeController = useRef<AbortController | null>(null);
  const busyRef = useRef(false);
  const lifetime = useRef({ active: true });
  const scheduler = useRef<QnaRefreshScheduler | null>(null);
  const onChangedRef = useRef(onChanged);
  onChangedRef.current = onChanged;
  const previousRefreshKey = useRef(refreshKey);
  const contextRef = useRef(context);
  contextRef.current = context;
  const queryKey = JSON.stringify([page, sort, filter]);
  const displayed = result?.queryKey === queryKey ? result.data : null;

  const clearPrivateState = useCallback(() => {
    setContext(null);
    setReportHasQuestions(false);
    setResult(null);
    setContent('');
    setAnonymous(false);
    retry.current = null;
    setNotice(null);
    setDeleteTarget(null);
    setError(null);
    setActionError(null);
    if (filter === 'PENDING') {
      setFilter('ALL');
      setPage(0);
    }
  }, [filter]);

  const refresh = useCallback(async () => {
    if (busyRef.current) return;
    setLoading(true);
    await requests.current.run(
      async (signal) => {
        const [nextContext, data] = await Promise.all([
          getQnaContext(meetingId, participantToken, signal),
          getQnaQuestions(meetingId, { page, sort, filter }, participantToken, signal),
        ]);
        const reportQuestions = nextContext.canManage
          ? filter === 'ALL'
            ? data
            : await getQnaQuestions(
                meetingId,
                { page: 0, size: 1, sort: 'NEWEST', filter: 'ALL' },
                participantToken,
                signal,
              )
          : null;
        return {
          nextContext,
          data,
          reportHasQuestions: (reportQuestions?.totalElements ?? 0) > 0,
        };
      },
      ({ nextContext, data, reportHasQuestions }) => {
        setContext(nextContext);
        setReportHasQuestions(reportHasQuestions);
        if (!nextContext.canManage || nextContext.meetingStatus !== 'IN_PROGRESS')
          setDeleteTarget(null);
        if (page > 0 && page >= data.totalPages) {
          setPage(Math.max(0, data.totalPages - 1));
          setResult(null);
          return;
        }
        setResult({ queryKey, data });
        setError(null);
        setLoading(false);
      },
      (failure) => {
        if (failure instanceof ManagementApiError && [401, 403, 404].includes(failure.status)) {
          clearPrivateState();
        }
        setError(qnaErrorMessage(failure));
        setLoading(false);
      },
    );
  }, [meetingId, participantToken, page, sort, filter, queryKey, clearPrivateState]);
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;

  useEffect(() => {
    const session = lifetime.current;
    const coordinator = requests.current;
    const engine = new QnaRefreshScheduler(
      () => refreshRef.current(),
      () => document.visibilityState === 'visible' && !busyRef.current,
      (failure) => setError(qnaErrorMessage(failure)),
    );
    scheduler.current = engine;
    session.active = true;
    const wake = () => engine.request();
    const visible = () => {
      if (document.visibilityState === 'visible') engine.request();
    };
    const timer = window.setInterval(() => {
      const status = contextRef.current?.meetingStatus;
      if (status !== 'COMPLETED' && status !== 'CANCELLED') engine.poll();
    }, 3000);
    window.addEventListener('focus', wake);
    window.addEventListener('online', wake);
    document.addEventListener('visibilitychange', visible);
    return () => {
      session.active = false;
      engine.dispose();
      if (scheduler.current === engine) scheduler.current = null;
      window.clearInterval(timer);
      window.removeEventListener('focus', wake);
      window.removeEventListener('online', wake);
      document.removeEventListener('visibilitychange', visible);
      coordinator.invalidate();
      writeController.current?.abort();
    };
  }, []);

  useEffect(() => {
    scheduler.current?.request();
    const coordinator = requests.current;
    return () => coordinator.invalidate();
  }, [refresh]);

  useEffect(() => {
    if (previousRefreshKey.current !== refreshKey) {
      previousRefreshKey.current = refreshKey;
      scheduler.current?.request();
    }
  }, [refreshKey]);

  async function act<T>(
    operation: (signal: AbortSignal) => Promise<T>,
    saved: string,
    onSaved?: (confirmed: T) => void,
    notify?: (confirmed: T) => boolean,
  ) {
    if (busyRef.current) return false;
    busyRef.current = true;
    setBusy(true);
    setNotice(null);
    setActionError(null);
    requests.current.invalidate();
    const controller = new AbortController();
    writeController.current = controller;
    try {
      const confirmed = await withManagementDeadline(operation, controller);
      if (!lifetime.current.active) return false;
      onSaved?.(confirmed);
      setNotice(saved);
      if (!notify || notify(confirmed)) {
        try {
          onChangedRef.current?.();
        } catch {
          /* A transport hint cannot turn a confirmed database write into a failure. */
        }
      }
      return true;
    } catch (failure) {
      if (!lifetime.current.active) return false;
      if (failure instanceof ManagementApiError && [401, 403, 404].includes(failure.status)) {
        clearPrivateState();
      }
      setActionError(
        failure instanceof ManagementApiError && failure.status === 409
          ? 'Dữ liệu đã thay đổi. Hãy kiểm tra dữ liệu mới trước khi thao tác lại.'
          : qnaErrorMessage(failure),
      );
      return false;
    } finally {
      if (lifetime.current.active) {
        busyRef.current = false;
        setBusy(false);
        writeController.current = null;
        scheduler.current?.request();
      }
    }
  }

  async function ask() {
    if (
      !context?.canAsk ||
      !context.enabled ||
      context.meetingStatus !== 'IN_PROGRESS' ||
      (anonymous && !context.anonymousAllowed) ||
      !content.trim() ||
      content.trim().length > 2000 ||
      busyRef.current
    )
      return;
    const submitted = { content: content.trim(), anonymous: anonymous && context.anonymousAllowed };
    const fingerprint = JSON.stringify(submitted);
    if (retry.current?.fingerprint !== fingerprint) {
      retry.current = { fingerprint, clientQuestionId: crypto.randomUUID() };
    }
    const clientQuestionId = retry.current.clientQuestionId;
    await act(
      (signal) =>
        askQnaQuestion(meetingId, { ...submitted, clientQuestionId }, participantToken, signal),
      'Câu hỏi đã được máy chủ lưu.',
      () => {
        setContent('');
        setAnonymous(false);
        retry.current = null;
      },
      (confirmed) => !confirmed.anonymous,
    );
  }

  function upvote(question: QnaQuestion) {
    if (!context?.canUpvote || busyRef.current) return;
    void act(
      (signal) =>
        setQnaUpvote(meetingId, question.id, !question.upvotedByMe, participantToken, signal),
      question.upvotedByMe ? 'Đã bỏ ủng hộ câu hỏi.' : 'Đã lưu lượt ủng hộ.',
    );
  }

  function saveSettings(input: QnaSettingsInput) {
    if (!context?.canManage || context.meetingStatus !== 'IN_PROGRESS' || busyRef.current) return;
    void act(
      (signal) => updateQnaSettings(meetingId, input, participantToken, signal),
      'Đã lưu cài đặt hỏi đáp.',
      (confirmed) => setContext(confirmed),
    );
  }

  function moderate(question: QnaQuestion, action: Exclude<QnaModerationAction, 'ANSWER'>) {
    if (!context?.canManage || context.meetingStatus !== 'IN_PROGRESS' || busyRef.current) return;
    if (action === 'DELETE') {
      setDeleteTarget(question);
      return;
    }
    void act(
      (signal) =>
        moderateQnaQuestion(
          meetingId,
          question.id,
          action,
          question.version,
          participantToken,
          signal,
        ),
      action === 'APPROVE' ? 'Đã duyệt câu hỏi.' : 'Đã ẩn câu hỏi.',
    );
  }

  async function answerQuestion(question: QnaQuestion, answer: string) {
    if (!context?.canManage || context.meetingStatus !== 'IN_PROGRESS' || busyRef.current)
      return false;
    return act(
      async (signal) => {
        const confirmed = await moderateQnaQuestion(
          meetingId,
          question.id,
          'ANSWER',
          question.version,
          participantToken,
          signal,
          answer,
        );
        if (confirmed.answer !== answer.trim())
          throw new ManagementApiError('Máy chủ chưa xác nhận lưu nội dung câu trả lời.', 502);
        return confirmed;
      },
      'Đã lưu câu trả lời.',
      (confirmed) =>
        setResult((current) =>
          current
            ? {
                ...current,
                data: {
                  ...current.data,
                  content: current.data.content.map((item) =>
                    item.id === confirmed.id ? confirmed : item,
                  ),
                },
              }
            : current,
        ),
    );
  }

  function confirmDelete() {
    if (
      !deleteTarget ||
      !context?.canManage ||
      context.meetingStatus !== 'IN_PROGRESS' ||
      busyRef.current
    )
      return;
    const question = deleteTarget;
    setDeleteTarget(null);
    void act(
      (signal) =>
        moderateQnaQuestion(
          meetingId,
          question.id,
          'DELETE',
          question.version,
          participantToken,
          signal,
        ),
      'Đã xóa câu hỏi.',
    );
  }

  function report() {
    if (!context?.canManage || !reportHasQuestions || busyRef.current) return;
    void act(
      async (signal) => {
        const file = await getQnaReport(meetingId, participantToken, signal);
        const verifiedContext = await getQnaContext(meetingId, participantToken, signal);
        if (!verifiedContext.canManage)
          throw new ManagementApiError('Bạn không còn quyền xuất báo cáo hỏi đáp.', 403);
        return { file, verifiedContext };
      },
      'Đã tải báo cáo hỏi đáp.',
      ({ file, verifiedContext }) => {
        const downloaded = saveQnaReportFile(
          file,
          () =>
            lifetime.current.active &&
            !writeController.current?.signal.aborted &&
            Boolean(contextRef.current?.canManage),
        );
        if (!downloaded)
          throw new ManagementApiError('Quyền xuất báo cáo hỏi đáp đã thay đổi.', 403);
        setContext(verifiedContext);
      },
      () => false,
    );
  }

  const ended = context?.meetingStatus === 'COMPLETED' || context?.meetingStatus === 'CANCELLED';

  return (
    <section className={styles.panel} aria-label="Hỏi đáp">
      <div className={styles.heading}>
        <h3>Hỏi đáp</h3>
        <div className={styles.headerActions}>
          {historyHref ? (
            <a
              className={styles.iconControl}
              href={historyHref}
              aria-label="Xem lịch sử hỏi đáp"
              title="Xem lịch sử hỏi đáp"
            >
              <QnaIcon name="history" />
            </a>
          ) : null}
          {context?.canManage && reportHasQuestions ? (
            <button
              type="button"
              className={styles.iconControl}
              disabled={busy || !!error}
              onClick={report}
              aria-busy={busy}
              aria-label="Tải báo cáo hỏi đáp CSV"
              title="Tải báo cáo CSV"
            >
              <QnaIcon name="download" />
            </button>
          ) : null}
          {context ? (
            <QnaSettings
              key={context.settingsVersion}
              context={context}
              busy={busy || !!error}
              onSave={saveSettings}
            />
          ) : null}
          {onClose ? (
            <button
              type="button"
              className={styles.iconControl}
              onClick={onClose}
              aria-label="Đóng bảng hỏi đáp"
              title="Đóng bảng hỏi đáp"
            >
              <QnaIcon name="close" />
            </button>
          ) : null}
        </div>
      </div>
      {error || actionError ? (
        <div className={styles.error} role="alert">
          <p>{actionError ?? error}</p>
          <button
            type="button"
            className={styles.secondary}
            disabled={busy}
            onClick={() => {
              setActionError(null);
              scheduler.current?.request();
            }}
          >
            Tải lại hỏi đáp
          </button>
          {actionError && retry.current ? (
            <p>
              Chưa nhận được xác nhận lưu. Bạn có thể tải lại để kiểm tra hoặc gửi lại cùng câu hỏi.
            </p>
          ) : null}
        </div>
      ) : null}
      {notice ? (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      ) : null}
      {context ? (
        <>
          {ended ? (
            <p className={styles.hint}>Cuộc họp đã kết thúc. Bạn đang xem lịch sử hỏi đáp.</p>
          ) : null}
          {!context.enabled && !ended ? (
            <p className={styles.hint}>Hỏi đáp đang tạm đóng. Các câu hỏi đã lưu vẫn có thể xem.</p>
          ) : null}
          {context.canAsk && context.enabled && context.meetingStatus === 'IN_PROGRESS' ? (
            <form
              className={styles.ask}
              onSubmit={(event) => {
                event.preventDefault();
                void ask();
              }}
            >
              <div className={styles.askHeading}>
                <label htmlFor={`qna-content-${meetingId}`}>Câu hỏi của bạn</label>
                <span id={`qna-length-${meetingId}`} className={styles.hint}>
                  {content.length}/2.000
                </span>
              </div>
              <textarea
                id={`qna-content-${meetingId}`}
                rows={2}
                placeholder="Nhập câu hỏi của bạn…"
                maxLength={2000}
                value={content}
                disabled={busy}
                onChange={(event) => {
                  setContent(event.target.value);
                  retry.current = null;
                  setActionError(null);
                  setNotice(null);
                }}
                aria-describedby={`qna-length-${meetingId}`}
              />
              <div className={styles.askActions}>
                {context.anonymousAllowed || anonymous ? (
                  <label className={styles.checkbox}>
                    <input
                      type="checkbox"
                      checked={anonymous}
                      disabled={busy}
                      onChange={(event) => {
                        setAnonymous(event.target.checked);
                        retry.current = null;
                        setActionError(null);
                        setNotice(null);
                      }}
                    />
                    Gửi ẩn danh
                  </label>
                ) : null}
                {anonymous && !context.anonymousAllowed ? (
                  <p className={styles.hint}>
                    Cuộc họp đã tắt hỏi ẩn danh. Hãy bỏ tùy chọn ẩn danh nếu bạn muốn gửi câu hỏi
                    kèm tên.
                  </p>
                ) : null}
                {context.moderationEnabled ? (
                  <p className={styles.hint}>
                    Câu hỏi sẽ được duyệt trước khi mọi người nhìn thấy.
                  </p>
                ) : null}
                <button
                  type="submit"
                  className={styles.primary}
                  disabled={
                    busy ||
                    !!error ||
                    !content.trim() ||
                    content.trim().length > 2000 ||
                    (anonymous && !context.anonymousAllowed)
                  }
                  aria-busy={busy}
                >
                  <QnaIcon name="send" />
                  {busy
                    ? 'Đang lưu...'
                    : retry.current && actionError
                      ? 'Thử gửi lại câu hỏi'
                      : 'Gửi câu hỏi'}
                </button>
              </div>
            </form>
          ) : !ended && context.enabled ? (
            <p className={styles.hint}>
              Bạn có thể xem câu hỏi. Hãy tham gia phòng họp để gửi câu hỏi và ủng hộ.
            </p>
          ) : null}
          <div className={styles.filters}>
            <label>
              <span className={styles.srOnly}>Sắp xếp</span>
              <select
                aria-label="Sắp xếp câu hỏi"
                title="Sắp xếp câu hỏi"
                value={sort}
                disabled={busy}
                onChange={(event) => {
                  setSort(event.target.value as QnaSort);
                  setPage(0);
                }}
              >
                <option value="NEWEST">Mới nhất</option>
                <option value="OLDEST">Cũ nhất</option>
                <option value="POPULAR">Nhiều ủng hộ</option>
              </select>
            </label>
            <label>
              <span className={styles.srOnly}>Danh sách</span>
              <select
                aria-label="Lọc câu hỏi"
                title="Lọc câu hỏi"
                value={filter}
                disabled={busy}
                onChange={(event) => {
                  setFilter(event.target.value as QnaFilter);
                  setPage(0);
                }}
              >
                <option value="ALL">Tất cả</option>
                <option value="MINE">Của tôi</option>
                {context.canManage ? <option value="PENDING">Chờ duyệt</option> : null}
              </select>
            </label>
          </div>
          <div className={styles.listHeader}>
            <h4 className={styles.listHeading}>
              Câu hỏi{displayed ? ` (${displayed.totalElements})` : ''}
            </h4>
            <button
              type="button"
              className={styles.iconControl}
              disabled={busy}
              onClick={() => scheduler.current?.request()}
              aria-label="Làm mới danh sách câu hỏi"
              title="Làm mới danh sách câu hỏi"
            >
              <QnaIcon name="refresh" />
            </button>
          </div>
          {loading && !displayed ? (
            <p className={styles.hint} role="status">
              Đang tải câu hỏi...
            </p>
          ) : null}
          {displayed ? (
            <>
              <QnaQuestionList
                questions={displayed.content}
                context={context}
                busy={busy || !!error}
                onUpvote={upvote}
                onModerate={moderate}
                onAnswer={answerQuestion}
              />
              {displayed.totalPages > 1 ? (
                <nav className={styles.pagination} aria-label="Phân trang câu hỏi">
                  <button
                    type="button"
                    className={styles.secondary}
                    disabled={busy || displayed.first || loading}
                    onClick={() => setPage((current) => current - 1)}
                    aria-label="Trang câu hỏi trước"
                  >
                    Trước
                  </button>
                  <span>
                    {displayed.page + 1}/{displayed.totalPages}
                  </span>
                  <button
                    type="button"
                    className={styles.secondary}
                    disabled={busy || displayed.last || loading}
                    onClick={() => setPage((current) => current + 1)}
                    aria-label="Trang câu hỏi tiếp theo"
                  >
                    Sau
                  </button>
                </nav>
              ) : null}
            </>
          ) : null}
        </>
      ) : loading ? (
        <p className={styles.hint} role="status">
          Đang tải hỏi đáp...
        </p>
      ) : null}
      {deleteTarget && context?.canManage && context.meetingStatus === 'IN_PROGRESS' ? (
        <QnaDeleteDialog
          question={deleteTarget}
          onConfirm={confirmDelete}
          onCancel={() => setDeleteTarget(null)}
        />
      ) : null}
    </section>
  );
}
