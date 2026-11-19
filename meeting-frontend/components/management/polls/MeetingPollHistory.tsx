'use client';

import { Fragment, useId, useState } from 'react';
import type { MeetingPoll } from '@/types/management/poll';
import { POLL_STATUS_LABELS } from '@/types/management/poll';
import { MeetingPollResults } from './MeetingPollResults';
import styles from '@/styles/management/Polls.module.css';

interface Props {
  polls: MeetingPoll[];
  editable: boolean;
  live: boolean;
  hasOpenPoll: boolean;
  busy: boolean;
  onEdit: (poll: MeetingPoll) => void;
  onOpen: (poll: MeetingPoll) => void;
  onCancel: (poll: MeetingPoll) => void;
}

export function MeetingPollHistory({
  polls,
  editable,
  live,
  hasOpenPoll,
  busy,
  onEdit,
  onOpen,
  onCancel,
}: Props) {
  const [expandedPollId, setExpandedPollId] = useState<number | null>(null);
  const resultIdPrefix = useId();

  if (polls.length === 0) {
    return <p className={styles.hint}>Chưa có nội dung biểu quyết khác trong danh sách.</p>;
  }

  return (
    <div className={styles.historyTableWrap}>
      <table className={styles.historyTable} aria-label="Danh sách biểu quyết">
        <thead>
          <tr>
            <th scope="col">Nội dung biểu quyết</th>
            <th scope="col">Trạng thái</th>
            <th scope="col">Đã biểu quyết</th>
            <th scope="col">Phiếu của bạn</th>
            <th scope="col">Thao tác</th>
          </tr>
        </thead>
        <tbody>
          {polls.map((poll) => {
            const expanded = expandedPollId === poll.id;
            const resultId = `${resultIdPrefix}-poll-results-${poll.id}`;
            const myChoices = poll.myVote
              ? poll.options
                  .filter((option) => poll.myVote?.optionIds.includes(option.id))
                  .map((option) => option.label)
                  .join(', ')
              : null;
            const canEditDraft = poll.canManage && poll.status === 'DRAFT' && editable;

            return (
              <Fragment key={poll.id}>
                <tr className={styles.historyItem}>
                  <th scope="row" className={styles.historyQuestion}>
                    {poll.question}
                  </th>
                  <td data-label="Trạng thái">
                    <span className={styles.historyStatus} data-status={poll.status}>
                      {POLL_STATUS_LABELS[poll.status]}
                    </span>
                  </td>
                  <td data-label="Đã biểu quyết">
                    <div className={styles.historyProgress}>
                      <span>
                        {poll.votedCount} / {poll.eligibleCount} người
                      </span>
                      <progress
                        max={Math.max(1, poll.eligibleCount)}
                        value={poll.votedCount}
                        aria-label={`Tiến độ biểu quyết: ${poll.question}`}
                      />
                    </div>
                  </td>
                  <td data-label="Phiếu của bạn">
                    {myChoices ? (
                      <span className={styles.historyVote}>{myChoices}</span>
                    ) : (
                      <span className={styles.hint} aria-label="Chưa có phiếu của bạn">
                        —
                      </span>
                    )}
                  </td>
                  <td className={styles.historyActionCell} data-label="Thao tác">
                    <div className={styles.historyActions}>
                      {poll.status !== 'DRAFT' ? (
                        <button
                          type="button"
                          className={styles.secondary}
                          aria-expanded={expanded}
                          aria-controls={resultId}
                          aria-label={`${expanded ? 'Thu gọn kết quả' : 'Xem kết quả'}: ${poll.question}`}
                          onClick={() => setExpandedPollId(expanded ? null : poll.id)}
                        >
                          {expanded ? 'Thu gọn' : 'Xem kết quả'}
                        </button>
                      ) : canEditDraft ? (
                        <>
                          <button
                            type="button"
                            className={styles.secondary}
                            disabled={busy}
                            aria-label={`Sửa biểu quyết: ${poll.question}`}
                            onClick={() => onEdit(poll)}
                          >
                            Sửa
                          </button>
                          <button
                            type="button"
                            className={styles.primary}
                            disabled={busy || !live || hasOpenPoll}
                            aria-label={`Mở biểu quyết: ${poll.question}`}
                            onClick={() => onOpen(poll)}
                          >
                            Mở biểu quyết
                          </button>
                          <button
                            type="button"
                            className={styles.danger}
                            disabled={busy}
                            aria-label={`Hủy biểu quyết: ${poll.question}`}
                            onClick={() => onCancel(poll)}
                          >
                            Hủy
                          </button>
                        </>
                      ) : (
                        <span className={styles.hint}>—</span>
                      )}
                    </div>
                  </td>
                </tr>
                {expanded && poll.status !== 'DRAFT' ? (
                  <tr className={styles.historyDetailRow}>
                    <td colSpan={5}>
                      <section
                        id={resultId}
                        className={styles.historyDetails}
                        aria-label={`Kết quả: ${poll.question}`}
                      >
                        <h5>{poll.question}</h5>
                        {poll.description ? <p>{poll.description}</p> : null}
                        <MeetingPollResults poll={poll} />
                      </section>
                    </td>
                  </tr>
                ) : null}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
