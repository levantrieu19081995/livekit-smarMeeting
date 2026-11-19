import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { MeetingPollResults } from './MeetingPollResults';
import type { MeetingPoll } from '@/types/management/poll';

const poll = {
  status: 'OPEN',
  resultVisibility: 'AFTER_CLOSE',
  canSeeResults: false,
  options: [{ id: 1, label: 'Phương án A' }],
  votedCount: 4,
  results: [{ optionId: 1, count: 3, percentage: 75 }],
} as MeetingPoll;

describe('Authoritative Poll results', () => {
  it('does not render option counts if the server denies visibility', () => {
    const markup = renderToStaticMarkup(<MeetingPollResults poll={poll} />);
    expect(markup).not.toContain('75%');
    expect(markup).toContain('sau khi đóng');
  });

  it('renders exact server aggregates and explains multiple-choice denominator', () => {
    const markup = renderToStaticMarkup(
      <MeetingPollResults
        poll={{ ...poll, status: 'CLOSED', canSeeResults: true, type: 'MULTIPLE_CHOICE' }}
      />,
    );
    expect(markup).toContain('3 phiếu · 75%');
    expect(markup).toContain('4 người đã bỏ phiếu');
    expect(markup).toContain('vượt 100%');
  });

  it('does not synthesize results when no aggregate is returned', () => {
    const markup = renderToStaticMarkup(
      <MeetingPollResults poll={{ ...poll, canSeeResults: true, results: null }} />,
    );
    expect(markup).not.toContain('phiếu ·');
  });
});
