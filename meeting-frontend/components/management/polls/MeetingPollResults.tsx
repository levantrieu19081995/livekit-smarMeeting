import type { MeetingPoll } from '@/types/management/poll';
import styles from '@/styles/management/Polls.module.css';

export function MeetingPollResults({ poll }: { poll: MeetingPoll }) {
  if (!poll.canSeeResults || !poll.results)
    return (
      <p className={styles.hint}>
        {poll.status === 'CANCELLED'
          ? 'Biểu quyết đã hủy; không công bố kết quả.'
          : poll.resultVisibility === 'AFTER_CLOSE' && poll.status === 'OPEN'
            ? 'Kết quả được hiển thị sau khi đóng biểu quyết.'
            : 'Kết quả chưa được công bố hoặc bạn không có quyền xem.'}
      </p>
    );
  return (
    <div aria-label="Kết quả biểu quyết">
      {poll.options.map((option) => {
        const result = poll.results!.find((row) => row.optionId === option.id);
        if (!result) return null;
        return (
          <div key={option.id} className={styles.result}>
            <div>
              <span>{option.label}</span>
              <strong>
                {result.count} phiếu · {result.percentage}%
              </strong>
            </div>
            <progress
              max={100}
              value={result.percentage}
              aria-label={`${option.label}: ${result.count} phiếu`}
            />
          </div>
        );
      })}
      <p className={styles.hint}>
        Tỷ lệ tính trên {poll.votedCount} người đã bỏ phiếu.
        {poll.type === 'MULTIPLE_CHOICE'
          ? ' Mỗi người được chọn nhiều phương án nên tổng tỷ lệ có thể vượt 100%.'
          : ''}
      </p>
      {poll.closedAt ? (
        <p className={styles.hint}>Đóng lúc {new Date(poll.closedAt).toLocaleString('vi-VN')}</p>
      ) : null}
    </div>
  );
}
