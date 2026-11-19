import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { MeetingPollBallot, validPollSelection } from './MeetingPollBallot';
import type { MeetingPoll } from '@/types/management/poll';

const poll: MeetingPoll = {
  id: 9,
  meetingId: 7,
  question: 'Thông qua?',
  description: null,
  type: 'YES_NO_ABSTAIN',
  status: 'OPEN',
  allowChangeVote: false,
  resultVisibility: 'AFTER_CLOSE',
  minSelections: 1,
  maxSelections: 1,
  createdAt: '2026-10-05T10:00:00',
  openedAt: '2026-10-05T10:01:00',
  closedAt: null,
  cancelledAt: null,
  canManage: false,
  options: [
    { id: 42, label: 'Tán thành', code: 'YES', sortOrder: 0 },
    { id: 43, label: 'Không tán thành', code: 'NO', sortOrder: 1 },
  ],
  eligibleParticipantKeys: [],
  eligibleCount: 2,
  votedCount: 0,
  canVote: true,
  canSeeResults: false,
  myVote: null,
  results: null,
};

describe('Participant ballot', () => {
  it('restores checked choices from the persisted vote and locks a non-changeable vote', () => {
    const markup = renderToStaticMarkup(
      <MeetingPollBallot
        poll={{
          ...poll,
          canVote: false,
          myVote: { optionIds: [42], submittedAt: 't1', updatedAt: 't1' },
        }}
        busy={false}
        onVote={async () => {}}
      />,
    );
    expect(markup).toContain('Phiếu đã lưu: Tán thành');
    expect(markup).toContain('checked=""');
    expect(markup).toContain('disabled=""');
    expect(markup).not.toContain('type="submit"');
  });

  it('does not present an unsaved selection as a saved vote', () => {
    const markup = renderToStaticMarkup(
      <MeetingPollBallot poll={poll} busy={false} onVote={async () => {}} />,
    );
    expect(markup).not.toContain('Phiếu đã lưu:');
    expect(markup).toContain('Gửi phiếu');
    expect(markup).toContain('type="radio"');
  });

  it('enforces own-option cardinality for single and multiple choice', () => {
    expect(validPollSelection(poll, [])).toBe(false);
    expect(validPollSelection(poll, [42, 43])).toBe(false);
    expect(validPollSelection(poll, [99])).toBe(false);
    expect(validPollSelection(poll, [42])).toBe(true);
    const multi = { ...poll, type: 'MULTIPLE_CHOICE' as const, minSelections: 2, maxSelections: 2 };
    expect(validPollSelection(multi, [42])).toBe(false);
    expect(validPollSelection(multi, [42, 42])).toBe(false);
    expect(validPollSelection(multi, [42, 43])).toBe(true);
  });

  it('renders closed and ineligible Polls without submission controls', () => {
    for (const state of [
      { ...poll, canVote: false },
      { ...poll, status: 'CLOSED' as const, canVote: false },
    ]) {
      const markup = renderToStaticMarkup(
        <MeetingPollBallot poll={state} busy={false} onVote={async () => {}} />,
      );
      expect(markup).not.toContain('type="submit"');
    }
  });
});
