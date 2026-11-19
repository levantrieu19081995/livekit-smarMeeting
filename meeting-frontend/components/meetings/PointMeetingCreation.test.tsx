import type { AnchorHTMLAttributes } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ManagementAuthContext } from '@/contexts/ManagementAuthContext';
import { HomeMeetingCreateOptions } from '@/components/home/HomeMeetingCreateOptions';
import { ManagementPointJoin } from '@/components/management-room/ManagementPointJoin';
import { MeetingForm } from './MeetingForm';
import { MeetingCreatePage } from './MeetingCreatePage';
import type { ManagementMeeting } from '@/types/management/meeting';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('next/link', () => ({
  default: ({ children, ...props }: AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a {...props}>{children}</a>
  ),
}));
vi.mock('@/hooks/useInstantMeetingCreation', () => ({
  useInstantMeetingCreation: () => ({
    pending: false,
    created: null,
    createInstant: vi.fn(),
    closeCreated: vi.fn(),
  }),
}));

function home(authenticated: boolean) {
  return renderToStaticMarkup(
    <ManagementAuthContext.Provider
      value={{
        loading: false,
        authenticated,
        user: null,
        refreshSession: async () => {},
        logout: async () => {},
      }}
    >
      <HomeMeetingCreateOptions />
    </ManagementAuthContext.Provider>,
  );
}

function createPage(mode: string) {
  return renderToStaticMarkup(
    <ManagementAuthContext.Provider
      value={{
        loading: false,
        authenticated: true,
        user: {
          publicId: 'creator-account',
          username: 'creator',
          fullName: 'Creator',
          email: 'creator@example.test',
          avatarUrl: null,
          provider: 'LOCAL',
          systemRole: 'USER',
        },
        refreshSession: async () => {},
        logout: async () => {},
      }}
    >
      <MeetingCreatePage initialMode={mode} />
    </ManagementAuthContext.Provider>,
  );
}

describe('POINT creation and join UI boundaries', () => {
  it('offers all existing creation options plus POINT on the homepage', () => {
    const markup = home(true);
    expect(markup).toContain('/management/meetings/create?mode=POINT');
    expect(markup).toContain('Bắt đầu cuộc họp tức thì');
    expect(markup).toContain('mode=LATER');
    expect(markup).toContain('mode=PRIVATE');
    expect(home(false)).toContain(
      '/auth/login?returnTo=%2Fmanagement%2Fmeetings%2Fcreate%3Fmode%3DPOINT',
    );
  });
  it('opens POINT directly in the full form with a list and the existing single save action', () => {
    const markup = createPage('POINT');
    expect(markup).toContain('Danh sách điểm cầu');
    expect(markup).toContain('Ngày họp');
    expect(markup).toContain('Giờ kết thúc');
    expect(markup.match(/type="submit"/g)).toHaveLength(1);
    expect(markup).not.toContain('Loại phòng');
  });
  it('keeps the ordinary private create form free of point selection fields', () => {
    const markup = createPage('PRIVATE');
    expect(markup).not.toContain('Danh sách điểm cầu');
    expect(markup).toContain('phòng riêng tư');
  });
  it('retains persisted points in the full edit form and freezes them after a connection exists', () => {
    const meeting: ManagementMeeting = {
      id: '7',
      title: 'Điểm cầu',
      roomName: 'room',
      startTime: '2026-10-08T09:00:00',
      endTime: '2026-10-08T11:00:00',
      status: 'IN_PROGRESS',
      accessType: 'PUBLIC',
      creationMode: 'POINT',
      participants: [],
      documents: [],
      points: [{ id: 11, name: 'Trường A' }],
      pointsEditable: false,
    };
    const markup = renderToStaticMarkup(
      <MeetingForm
        mode="edit"
        initialMeeting={meeting}
        submitting={false}
        onSubmit={async () => {}}
        onCancel={() => {}}
      />,
    );
    expect(markup).toContain('Trường A');
    expect(markup).toContain('readonly');
    expect(markup).toContain('bảo toàn lịch sử');
    expect(markup).toContain('value="09:00"');
    expect(markup).toContain('value="11:00"');
  });
  it('requires explicit point selection before continuing and handles no available points', () => {
    const meeting = {
      id: 7,
      title: 'Điểm cầu',
      startTime: null,
      endTime: null,
      status: 'IN_PROGRESS' as const,
      accessType: 'PUBLIC' as const,
      creationMode: 'POINT' as const,
      points: [{ id: 11, name: 'Trường A' }],
    };
    const markup = renderToStaticMarkup(
      <ManagementPointJoin meeting={meeting} onSubmit={() => {}} onRefresh={() => {}} />,
    );
    expect(markup).toContain('required');
    expect(markup).toContain('value="11"');
    expect(markup).toMatch(/type="submit"[^>]*disabled/);
    const empty = renderToStaticMarkup(
      <ManagementPointJoin
        meeting={{ ...meeting, points: [] }}
        onSubmit={() => {}}
        onRefresh={() => {}}
      />,
    );
    expect(empty).toContain('Tải lại danh sách');
    expect(empty).toContain('chưa có điểm cầu khả dụng');
    const remembered = renderToStaticMarkup(
      <ManagementPointJoin
        meeting={meeting}
        initialPointId={11}
        onSubmit={() => {}}
        onRefresh={() => {}}
      />,
    );
    expect(remembered).toContain('Chọn điểm cầu của bạn');
    expect(remembered).toMatch(/value="11" selected/);
    expect(remembered).not.toMatch(/type="submit"[^>]*disabled/);
  });

  it('initializes a recovered create form with its metadata, points and designated roster', () => {
    const markup = renderToStaticMarkup(
      <MeetingForm
        mode="create"
        creationMode="POINT"
        submitting={false}
        onSubmit={async () => {}}
        onCancel={() => {}}
        initialValues={{
          title: 'Recovered meeting',
          description: 'Recovered content',
          accessType: 'PUBLIC',
          creationMode: 'POINT',
          startTime: '2026-10-08T09:00:00',
          endTime: '2026-10-08T11:00:00',
          pointNames: ['Trường A'],
          participants: [
            {
              userId: 'chair',
              name: 'Recovered chairman',
              email: 'chair@example.test',
              role: 'CHAIRMAN',
            },
          ],
          documents: [],
        }}
      />,
    );
    expect(markup).toContain('value="Recovered meeting"');
    expect(markup).toContain('Recovered content');
    expect(markup).toContain('Trường A');
    expect(markup).toContain('Recovered chairman');
    expect(markup).toContain('value="09:00"');
    expect(markup).toContain('value="11:00"');
  });
});
