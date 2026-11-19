'use client';

import { useState } from 'react';
import type { MeetingPoll } from '@/types/management/poll';
import styles from '@/styles/management/Polls.module.css';

interface Props {
  poll: MeetingPoll;
  busy: boolean;
  onVote: (optionIds: number[]) => Promise<void>;
}

export function validPollSelection(poll: MeetingPoll, ids: number[]) {
  return (
    ids.length >= poll.minSelections &&
    ids.length <= poll.maxSelections &&
    new Set(ids).size === ids.length &&
    ids.every((id) => poll.options.some((option) => option.id === id))
  );
}

// The parent keys this form by Poll and persisted vote revision. Background
// refreshes preserve an unsent selection; a confirmed server revision restores it.
export function MeetingPollBallot({ poll, busy, onVote }: Props) {
  const [selected, setSelected] = useState(poll.myVote?.optionIds ?? []);
  const multiple = poll.type === 'MULTIPLE_CHOICE';
  const disabled = busy || !poll.canVote;
  const savedLabels = poll.options
    .filter((option) => poll.myVote?.optionIds.includes(option.id))
    .map((option) => option.label)
    .join(', ');

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        if (!disabled && validPollSelection(poll, selected)) void onVote(selected);
      }}
    >
      {poll.myVote ? (
        <p className={styles.saved} role="status">
          Phiếu đã lưu: {savedLabels}
        </p>
      ) : null}
      {poll.status === 'OPEN' && poll.canVote ? (
        <p className={styles.hint}>
          {multiple
            ? `Chọn từ ${poll.minSelections} đến ${poll.maxSelections} phương án.`
            : 'Chọn một phương án.'}
          {poll.myVote ? ' Bạn có thể thay đổi phiếu khi biểu quyết còn mở.' : ''}
        </p>
      ) : (
        <p className={styles.hint}>
          {poll.status !== 'OPEN'
            ? 'Biểu quyết đã kết thúc.'
            : poll.myVote
              ? 'Phiếu đã được ghi nhận; biểu quyết này không cho phép đổi phiếu.'
              : 'Bạn không có quyền bỏ phiếu hoặc cuộc họp đã kết thúc.'}
        </p>
      )}
      <fieldset className={styles.ballot} disabled={disabled}>
        <legend className={styles.srOnly}>Lựa chọn biểu quyết</legend>
        {poll.options.map((option) => (
          <label key={option.id} className={styles.choice}>
            <input
              type={multiple ? 'checkbox' : 'radio'}
              name={`poll-${poll.id}`}
              checked={selected.includes(option.id)}
              onChange={(event) =>
                setSelected((current) =>
                  multiple
                    ? event.target.checked
                      ? [...current, option.id]
                      : current.filter((id) => id !== option.id)
                    : [option.id],
                )
              }
            />
            <span>{option.label}</span>
          </label>
        ))}
      </fieldset>
      {poll.canVote ? (
        <button
          type="submit"
          className={styles.primary}
          disabled={busy || !validPollSelection(poll, selected)}
        >
          {busy ? 'Đang gửi...' : poll.myVote ? 'Cập nhật phiếu' : 'Gửi phiếu'}
        </button>
      ) : null}
      <p className={styles.hint}>Mỗi người tham gia có một phiếu biểu quyết.</p>
    </form>
  );
}
