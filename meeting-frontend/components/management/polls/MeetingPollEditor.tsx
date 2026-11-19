'use client';

import { useState } from 'react';
import type {
  MeetingPoll,
  PollContext,
  PollDraftInput,
  PollResultVisibility,
  PollType,
} from '@/types/management/poll';
import styles from '@/styles/management/Polls.module.css';

interface Props {
  initial: MeetingPoll | null;
  context: PollContext;
  busy: boolean;
  onSave: (input: PollDraftInput) => Promise<void>;
  onCancel: () => void;
}

export function MeetingPollEditor({ initial, busy, onSave, onCancel }: Props) {
  const [question, setQuestion] = useState(initial?.question ?? '');
  const [description, setDescription] = useState(initial?.description ?? '');
  const [type, setType] = useState<PollType>(initial?.type ?? 'YES_NO_ABSTAIN');
  const [labels, setLabels] = useState(
    initial?.options.map((o) => o.label).join('\n') ?? 'Phương án A\nPhương án B',
  );
  const [allowChange, setAllowChange] = useState(initial?.allowChangeVote ?? false);
  const [visibility, setVisibility] = useState<PollResultVisibility>(
    initial?.resultVisibility ?? 'AFTER_CLOSE',
  );
  const [minimum, setMinimum] = useState(initial?.minSelections ?? 1);
  const [maximum, setMaximum] = useState(initial?.maxSelections ?? 2);
  const [error, setError] = useState<string | null>(null);

  async function save(event: React.FormEvent) {
    event.preventDefault();
    const options = labels
      .split('\n')
      .map((label) => label.trim())
      .filter(Boolean);
    if (!question.trim()) {
      setError('Nhập câu hỏi biểu quyết.');
      return;
    }
    if (
      type !== 'YES_NO_ABSTAIN' &&
      (options.length < 2 ||
        options.length > 20 ||
        new Set(options.map((label) => label.toLocaleLowerCase('vi'))).size !== options.length)
    ) {
      setError('Nhập từ 2 đến 20 phương án khác nhau.');
      return;
    }
    if (
      type === 'MULTIPLE_CHOICE' &&
      (minimum < 1 || maximum < minimum || maximum > options.length)
    ) {
      setError('Số lựa chọn tối thiểu/tối đa phải phù hợp với các phương án.');
      return;
    }
    setError(null);
    await onSave({
      question: question.trim(),
      description: description.trim() || undefined,
      type,
      allowChangeVote: allowChange,
      resultVisibility: visibility,
      minSelections: type === 'MULTIPLE_CHOICE' ? minimum : 1,
      maxSelections: type === 'MULTIPLE_CHOICE' ? maximum : 1,
      options: type === 'YES_NO_ABSTAIN' ? undefined : options.map((label) => ({ label })),
      eligibleParticipantKeys: [],
    });
  }

  return (
    <form className={styles.editor} onSubmit={(event) => void save(event)}>
      <h4>{initial ? 'Sửa bản nháp' : 'Tạo biểu quyết'}</h4>
      <fieldset disabled={busy}>
        <label>
          Câu hỏi
          <textarea
            required
            maxLength={1000}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            rows={3}
          />
        </label>
        <label>
          Mô tả
          <textarea
            maxLength={20000}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={2}
          />
        </label>
        <label>
          Loại biểu quyết
          <select value={type} onChange={(e) => setType(e.target.value as PollType)}>
            <option value="YES_NO_ABSTAIN">Tán thành / Không tán thành / Không ý kiến</option>
            <option value="SINGLE_CHOICE">Chọn một phương án</option>
            <option value="MULTIPLE_CHOICE">Chọn nhiều phương án</option>
          </select>
        </label>
        {type === 'YES_NO_ABSTAIN' ? (
          <p className={styles.hint}>Ba lựa chọn: Tán thành, Không tán thành, Không ý kiến.</p>
        ) : (
          <label>
            Phương án — mỗi dòng một phương án
            <textarea value={labels} onChange={(e) => setLabels(e.target.value)} rows={4} />
          </label>
        )}
        {type === 'MULTIPLE_CHOICE' ? (
          <div className={styles.limits}>
            <label>
              Tối thiểu
              <input
                type="number"
                min={1}
                max={20}
                value={minimum}
                onChange={(e) => setMinimum(Number(e.target.value))}
              />
            </label>
            <label>
              Tối đa
              <input
                type="number"
                min={1}
                max={20}
                value={maximum}
                onChange={(e) => setMaximum(Number(e.target.value))}
              />
            </label>
          </div>
        ) : null}
        <label>
          Kết quả
          <select
            value={visibility}
            onChange={(e) => setVisibility(e.target.value as PollResultVisibility)}
          >
            <option value="AFTER_CLOSE">Hiển thị sau khi đóng</option>
            <option value="LIVE">Hiển thị trong lúc biểu quyết</option>
            <option value="AUTHORIZED_ONLY">Chỉ người tham gia được xem</option>
          </select>
        </label>
        <label className={styles.check}>
          <input
            type="checkbox"
            checked={allowChange}
            onChange={(e) => setAllowChange(e.target.checked)}
          />
          Cho phép thay đổi phiếu khi còn mở
        </label>
        <p className={styles.hint}>
          Mọi người tham gia cuộc họp đều có thể bỏ phiếu, kể cả khách và người vào sau.
        </p>
        {error ? (
          <p className={styles.error} role="alert">
            {error}
          </p>
        ) : null}
        <div className={styles.actions}>
          <button type="submit" className={styles.primary}>
            Lưu bản nháp
          </button>
          <button type="button" className={styles.secondary} onClick={onCancel}>
            Quay lại
          </button>
        </div>
      </fieldset>
    </form>
  );
}
