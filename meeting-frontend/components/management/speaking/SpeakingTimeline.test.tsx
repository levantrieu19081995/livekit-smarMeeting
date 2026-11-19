import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { SpeakingTimeline } from './SpeakingTimeline';
import type { SpeakingRequest } from '@/types/management/speaking';

const row: SpeakingRequest = {
  id: 1,
  requestId: 'private-id',
  version: 0,
  pointId: 3,
  pointName: 'Trường A',
  speakerName: 'Nguyễn Văn Bình',
  requestedByDisplayName: 'Đại diện Trường A',
  approvedByDisplayName: null,
  finishedByDisplayName: null,
  status: 'WAITING',
  requestedAt: '2026-10-08T02:10:00Z',
  approvedAt: null,
  finishedAt: null,
  isOwn: false,
  canApprove: false,
  canFinish: false,
  canCancel: false,
};
function markup(overrides: Partial<SpeakingRequest> = {}, readOnly = false, busy = false) {
  return renderToStaticMarkup(
    <SpeakingTimeline
      rows={[{ ...row, ...overrides }]}
      readOnly={readOnly}
      busy={busy}
      onAction={() => {}}
    />,
  );
}

describe('Shared-device timeline and authorized actions', () => {
  it('separates named speaker from representative, escapes user content and does not expose internal IDs', () => {
    const output = markup({ speakerName: '<script>alert(1)</script>' });
    expect(output).toContain('&lt;script&gt;');
    expect(output).not.toContain('<script>');
    expect(output).toContain('Đại diện gửi:');
    expect(output).toContain('Trường A');
    expect(output).not.toContain('private-id');
  });
  it('does not infer approval rights from presence in a public point meeting', () => {
    const output = markup({ isOwn: true, canCancel: true });
    expect(output).toContain('Hủy yêu cầu');
    expect(output).not.toContain('>Duyệt<');
    expect(output).not.toContain('>Kết thúc<');
  });
  it('shows approval only for server-authorized waiting requests', () => {
    expect(markup({ canApprove: true })).toContain('>Duyệt<');
    expect(markup({ canApprove: true, status: 'FINISHED' })).not.toContain('>Duyệt<');
  });
  it('hides all mutations in post-meeting history even with stale capability flags', () => {
    const output = markup({ canApprove: true, canCancel: true }, true);
    expect(output).not.toContain('<button');
    expect(output).toContain('Chờ duyệt');
  });
  it('renders server timestamps in the meeting timezone and disables duplicate clicks during writes', () => {
    const output = markup({ canApprove: true }, false, true);
    expect(output).toContain('09:10:00');
    expect(output).toContain('disabled=""');
  });
  it('preserves approver and finisher history and status', () => {
    const output = markup({
      status: 'FINISHED',
      approvedAt: '2026-10-08T02:12:00Z',
      finishedAt: '2026-10-08T02:15:00Z',
      approvedByDisplayName: 'Chủ tọa',
      finishedByDisplayName: 'Thư ký',
    });
    expect(output).toContain('Đã kết thúc');
    expect(output).toContain('09:12:00');
    expect(output).toContain('09:15:00');
    expect(output).toContain('Chủ tọa');
    expect(output).toContain('Thư ký');
  });
});
