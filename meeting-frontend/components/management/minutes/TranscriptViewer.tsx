import { useMemo, useState } from 'react';
import type { MinutesParticipant, MinutesTranscriptEntry } from '@/types/management/minutes';
import styles from '@/styles/management/Minutes.module.css';

interface TranscriptViewerProps {
  entries: MinutesTranscriptEntry[];
  participants: MinutesParticipant[];
}

export function TranscriptViewer({ entries, participants }: TranscriptViewerProps) {
  const [search, setSearch] = useState('');
  const [speakerId, setSpeakerId] = useState('ALL');
  const filteredEntries = useMemo(() => {
    const query = search.trim().toLocaleLowerCase('vi-VN');
    return entries.filter((entry) => {
      const matchesSpeaker = speakerId === 'ALL' || entry.participantId === speakerId;
      const matchesSearch =
        !query || `${entry.speakerName} ${entry.text}`.toLocaleLowerCase('vi-VN').includes(query);
      return matchesSpeaker && matchesSearch;
    });
  }, [entries, search, speakerId]);

  return (
    <section className={styles.transcriptPanel}>
      <div className={styles.sectionHeading}>
        <div>
          <h3>Bản ghi lời nói</h3>
          <p>Transcript gốc chỉ đọc và không được chỉnh sửa.</p>
        </div>
        <span className={styles.readOnlyBadge}>Read only</span>
      </div>
      <div className={styles.transcriptFilters}>
        <label className={styles.searchField}>
          <span className={styles.visuallyHidden}>Tìm trong bản ghi</span>
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M10.5 3a7.5 7.5 0 1 1-4.77 13.29L2.5 19.52l-1.42-1.41 3.24-3.24A7.5 7.5 0 0 1 10.5 3Zm0 2a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11Z" />
          </svg>
          <input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Tìm trong bản ghi..."
          />
        </label>
        <label className={styles.filterField}>
          <span>Người nói</span>
          <select value={speakerId} onChange={(event) => setSpeakerId(event.target.value)}>
            <option value="ALL">Tất cả người nói</option>
            {participants.map((participant) => (
              <option key={participant.id} value={participant.id}>
                {participant.name}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className={styles.transcriptList}>
        {filteredEntries.map((entry) => (
          <article key={entry.id} className={styles.transcriptEntry}>
            <time>{entry.timestamp}</time>
            <div>
              <strong>{entry.speakerName}</strong>
              <p>{entry.text}</p>
            </div>
          </article>
        ))}
        {filteredEntries.length === 0 ? (
          <div className={styles.inlineEmpty}>Không tìm thấy nội dung phù hợp trong bản ghi.</div>
        ) : null}
      </div>
    </section>
  );
}
