import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getMeetingDocuments } from '@/services/management/managementDocumentService';
import { managementFetchBlob, managementFetchJson } from '@/services/management/httpClient';
import {
  contentJsonToEditorContent,
  createMinutesVersion,
  downloadMinutesPdf,
  editorContentToContentJson,
  getMinutesDetail,
  getMinutesTemplates,
  getMinutesTranscript,
  getMinutesVersions,
  renderMinutesVersion,
  updateMinutesDraft,
} from './managementMinutesService';
import type { MinutesContentJson, MinutesVersionApiResponse } from '@/types/management/minutes';

vi.mock('@/services/management/httpClient', () => ({
  ManagementApiError: class ManagementApiError extends Error {
    status: number;

    constructor(status: number, message: string) {
      super(message);
      this.status = status;
    }
  },
  managementFetchBlob: vi.fn(),
  managementFetchJson: vi.fn(),
}));

vi.mock('@/services/management/managementDocumentService', () => ({
  getMeetingDocuments: vi.fn(),
}));

const mockedFetchJson = vi.mocked(managementFetchJson);
const mockedFetchBlob = vi.mocked(managementFetchBlob);
const mockedGetMeetingDocuments = vi.mocked(getMeetingDocuments);

describe('draft revision contract', () => {
  beforeEach(() => mockedFetchJson.mockReset());
  it('sends the observed revision and retains the updated revision from the receipt', async () => {
    mockedFetchJson.mockResolvedValue({
      ...minutesVersionResponse({ mainContent: 'Saved' }),
      revision: 4,
    });
    const result = await updateMinutesDraft('12', 1, { mainContent: 'Saved' }, 3);
    expect(mockedFetchJson).toHaveBeenCalledWith('/api/meetings/12/minutes/versions/1', {
      method: 'PUT',
      body: JSON.stringify({ content: { mainContent: 'Saved' }, expectedRevision: 3 }),
    });
    expect(result.revision).toBe(4);
  });
  it('refuses a write without an observed valid revision before any network request', async () => {
    await expect(updateMinutesDraft('12', 1, {}, undefined as unknown as number)).rejects.toThrow(
      'tải lại',
    );
    expect(mockedFetchJson).not.toHaveBeenCalled();
  });
  it('does not repeat a rejected stale draft write', async () => {
    mockedFetchJson.mockRejectedValueOnce(new Error('MINUTES_REVISION_CONFLICT'));
    await expect(updateMinutesDraft('12', 1, {}, 2)).rejects.toThrow('MINUTES_REVISION_CONFLICT');
    expect(mockedFetchJson).toHaveBeenCalledTimes(1);
  });
});

function pdfBlob(content = 'pdf', type = 'application/pdf') {
  return new Blob([content], { type });
}

function blobDownloadResponse(
  blob: Blob,
  headers: Record<string, string> = { 'content-type': 'application/pdf' },
) {
  return {
    blob,
    response: {
      headers: new Headers(headers),
    } as Response,
  };
}

function minutesVersionResponse(content: MinutesContentJson): MinutesVersionApiResponse {
  return {
    id: 1,
    meetingId: 12,
    version: 1,
    status: 'DRAFT',
    source: 'SECRETARY_EDITED',
    content,
    createdBy: {
      id: 'user-1',
      displayName: 'Secretary',
    },
    createdAt: '2026-08-27T08:00:00Z',
    updatedAt: '2026-08-27T08:00:00Z',
    finalizedAt: null,
  };
}

describe('managementMinutesService editor version flow', () => {
  beforeEach(() => {
    mockedFetchJson.mockReset();
    mockedFetchBlob.mockReset();
    mockedGetMeetingDocuments.mockReset();
  });

  it('converts null content to empty editor content', () => {
    expect(contentJsonToEditorContent(null)).toEqual({
      mainContent: '',
      conclusion: '',
    });
  });

  it('converts string content to main content', () => {
    expect(contentJsonToEditorContent('plain minutes')).toEqual({
      mainContent: 'plain minutes',
      conclusion: '',
    });
  });

  it('uses canonical main content and conclusion', () => {
    expect(contentJsonToEditorContent({ mainContent: 'A', conclusion: 'B' })).toEqual({
      mainContent: 'A',
      conclusion: 'B',
    });
  });

  it('keeps an empty canonical main content ahead of legacy text', () => {
    expect(contentJsonToEditorContent({ mainContent: '', text: 'legacy' }).mainContent).toBe('');
  });

  it('falls back to legacy text', () => {
    expect(contentJsonToEditorContent({ text: 'legacy text' }).mainContent).toBe('legacy text');
  });

  it('falls back to legacy content', () => {
    expect(contentJsonToEditorContent({ content: 'legacy content' }).mainContent).toBe(
      'legacy content',
    );
  });

  it('falls back to legacy minutesText', () => {
    expect(contentJsonToEditorContent({ minutesText: 'legacy minutes' }).mainContent).toBe(
      'legacy minutes',
    );
  });

  it('joins legacy discussion items with newlines', () => {
    expect(contentJsonToEditorContent({ discussionItems: ['A', 'B'] }).mainContent).toBe('A\nB');
  });

  it('joins legacy conclusions with newlines', () => {
    expect(contentJsonToEditorContent({ conclusions: ['K1', 'K2'] }).conclusion).toBe('K1\nK2');
  });

  it('persists editor content with canonical keys', () => {
    expect(editorContentToContentJson('A', 'B')).toEqual({
      mainContent: 'A',
      conclusion: 'B',
    });
  });

  it('preserves unrelated previous object fields when writing canonical keys', () => {
    expect(editorContentToContentJson('A', 'B', { location: 'Room 1', text: 'old' })).toEqual({
      location: 'Room 1',
      text: 'old',
      mainContent: 'A',
      conclusion: 'B',
    });
  });

  it('creates an edited version from V1 with the selected baseVersion', async () => {
    const content = { mainContent: 'A', conclusion: 'B' };
    mockedFetchJson.mockResolvedValue(minutesVersionResponse(content));

    await createMinutesVersion('12', 1, content);

    expect(mockedFetchJson).toHaveBeenCalledWith('/api/meetings/12/minutes/versions', {
      method: 'POST',
      body: JSON.stringify({
        baseVersion: 1,
        content,
      }),
    });
    expect(JSON.parse(mockedFetchJson.mock.calls[0][1]?.body as string)).toEqual({
      baseVersion: 1,
      content,
    });
  });

  it('creates the next edited version with the selected baseVersion', async () => {
    const content = { mainContent: 'A', conclusion: 'B' };
    mockedFetchJson.mockResolvedValue(minutesVersionResponse(content));

    await createMinutesVersion('12', 2, content);

    expect(JSON.parse(mockedFetchJson.mock.calls[0][1]?.body as string).baseVersion).toBe(2);
  });

  it('maps a backend SYSTEM_GENERATED V1 without treating it as AI', async () => {
    const content = { mainContent: 'Voice and chat content', conclusion: 'Decision' };
    mockedFetchJson.mockResolvedValue([
      {
        ...minutesVersionResponse(content),
        source: 'SYSTEM_GENERATED',
        createdBy: null,
      },
    ]);

    const versions = await getMinutesVersions('12');

    expect(versions[0]).toMatchObject({
      source: 'SYSTEM_GENERATED',
      createdBy: 'Hệ thống tạo',
      content: JSON.stringify(content, null, 2),
      contentJson: content,
    });
  });

  it('gets the minutes template list without local defaults', async () => {
    const templates = [
      {
        code: 'CUSC_STANDARD',
        name: 'Biên bản họp CUSC',
        description: 'Mẫu biên bản họp CUSC - BM07.40/PM/CUSC',
      },
    ];
    mockedFetchJson.mockResolvedValue(templates);

    await expect(getMinutesTemplates()).resolves.toBe(templates);

    expect(mockedFetchJson).toHaveBeenCalledWith('/api/minutes/templates');
  });

  it('renders the selected minutes version with the selected template', async () => {
    mockedFetchJson.mockResolvedValue({
      meetingId: 12,
      version: 2,
      status: 'FINAL',
      template: {
        code: 'CUSC_STANDARD',
        name: 'Biên bản họp CUSC',
        description: 'Mẫu biên bản họp CUSC - BM07.40/PM/CUSC',
      },
      html: '<!DOCTYPE html><html>...</html>',
      canExportPdf: false,
    });

    await renderMinutesVersion('12', 2, 'CUSC_STANDARD');

    expect(mockedFetchJson).toHaveBeenCalledWith('/api/meetings/12/minutes/versions/2/render', {
      method: 'POST',
      body: JSON.stringify({
        templateCode: 'CUSC_STANDARD',
      }),
    });
  });

  it('encodes the meeting id when rendering a minutes version', async () => {
    mockedFetchJson.mockResolvedValue({
      meetingId: 12,
      version: 2,
      status: 'DRAFT',
      template: {
        code: 'CUSC_STANDARD',
        name: 'Biên bản họp CUSC',
        description: 'Mẫu biên bản họp CUSC - BM07.40/PM/CUSC',
      },
      html: '<!DOCTYPE html><html>...</html>',
      canExportPdf: false,
    });

    await renderMinutesVersion('meeting/12', 2, 'CUSC_STANDARD');

    expect(mockedFetchJson).toHaveBeenCalledWith(
      '/api/meetings/meeting%2F12/minutes/versions/2/render',
      expect.any(Object),
    );
  });

  it('keeps rendered HTML exactly as returned by the backend', async () => {
    const html = '<!DOCTYPE html><html>...</html>';
    mockedFetchJson.mockResolvedValue({
      meetingId: 12,
      version: 2,
      status: 'FINAL',
      template: {
        code: 'CUSC_STANDARD',
        name: 'Biên bản họp CUSC',
        description: 'Mẫu biên bản họp CUSC - BM07.40/PM/CUSC',
      },
      html,
      canExportPdf: false,
    });

    await expect(renderMinutesVersion('12', 2, 'CUSC_STANDARD')).resolves.toMatchObject({
      html,
    });
  });

  it('keeps canExportPdf=false from the backend response', async () => {
    mockedFetchJson.mockResolvedValue({
      meetingId: 12,
      version: 2,
      status: 'FINAL',
      template: {
        code: 'CUSC_STANDARD',
        name: 'Biên bản họp CUSC',
        description: 'Mẫu biên bản họp CUSC - BM07.40/PM/CUSC',
      },
      html: '<!DOCTYPE html><html>...</html>',
      canExportPdf: false,
    });

    await expect(renderMinutesVersion('12', 2, 'CUSC_STANDARD')).resolves.toMatchObject({
      canExportPdf: false,
    });
  });

  it('downloads a PDF for the selected minutes version and template', async () => {
    const blob = pdfBlob();
    mockedFetchBlob.mockResolvedValue(blobDownloadResponse(blob));

    await downloadMinutesPdf('12', 2, 'CUSC_STANDARD');

    expect(mockedFetchBlob).toHaveBeenCalledWith('/api/meetings/12/minutes/versions/2/export/pdf', {
      method: 'POST',
      body: JSON.stringify({
        templateCode: 'CUSC_STANDARD',
      }),
    });
  });

  it('encodes the meeting id when downloading a PDF', async () => {
    mockedFetchBlob.mockResolvedValue(blobDownloadResponse(pdfBlob()));

    await downloadMinutesPdf('meeting/12', 2, 'CUSC_STANDARD');

    expect(mockedFetchBlob).toHaveBeenCalledWith(
      '/api/meetings/meeting%2F12/minutes/versions/2/export/pdf',
      expect.any(Object),
    );
  });

  it('uses the filename from Content-Disposition when downloading a PDF', async () => {
    const blob = pdfBlob();
    mockedFetchBlob.mockResolvedValue(
      blobDownloadResponse(blob, {
        'content-type': 'application/pdf',
        'content-disposition': 'attachment; filename="bien-ban-hop-12-v2.pdf"',
      }),
    );

    await expect(downloadMinutesPdf('12', 2, 'CUSC_STANDARD')).resolves.toEqual({
      blob,
      filename: 'bien-ban-hop-12-v2.pdf',
    });
  });

  it('uses a deterministic fallback filename when Content-Disposition is missing', async () => {
    const blob = pdfBlob();
    mockedFetchBlob.mockResolvedValue(blobDownloadResponse(blob));

    await expect(downloadMinutesPdf('12', 2, 'CUSC_STANDARD')).resolves.toEqual({
      blob,
      filename: 'bien-ban-hop-12-v2.pdf',
    });
  });

  it('accepts an application/pdf content type', async () => {
    mockedFetchBlob.mockResolvedValue(
      blobDownloadResponse(pdfBlob(), {
        'content-type': 'application/pdf;charset=utf-8',
      }),
    );

    await expect(downloadMinutesPdf('12', 2, 'CUSC_STANDARD')).resolves.toMatchObject({
      filename: 'bien-ban-hop-12-v2.pdf',
    });
  });

  it('rejects a non-PDF content type', async () => {
    mockedFetchBlob.mockResolvedValue(
      blobDownloadResponse(pdfBlob('<html></html>', 'text/html'), {
        'content-type': 'text/html',
      }),
    );

    await expect(downloadMinutesPdf('12', 2, 'CUSC_STANDARD')).rejects.toThrow(
      'Máy chủ không trả về tệp PDF hợp lệ.',
    );
  });

  it('rejects an empty PDF blob', async () => {
    mockedFetchBlob.mockResolvedValue(
      blobDownloadResponse(new Blob([], { type: 'application/pdf' })),
    );

    await expect(downloadMinutesPdf('12', 2, 'CUSC_STANDARD')).rejects.toThrow(
      'Tệp PDF trả về rỗng.',
    );
  });

  it('gets minutes transcript from the meeting transcript endpoint', async () => {
    mockedFetchJson.mockResolvedValue([]);

    await getMinutesTranscript('12');

    expect(mockedFetchJson).toHaveBeenCalledWith('/api/meetings/12/minutes/transcript');
  });

  it('encodes the meeting id when getting minutes transcript', async () => {
    mockedFetchJson.mockResolvedValue([]);

    await getMinutesTranscript('meeting/12');

    expect(mockedFetchJson).toHaveBeenCalledWith('/api/meetings/meeting%2F12/minutes/transcript');
  });

  it('maps account attendee ids to participant ids', async () => {
    mockedFetchJson.mockResolvedValue([
      transcriptResponse({ id: 1, attendeeId: 'account:user-1' }),
    ]);

    await expect(getMinutesTranscript('12')).resolves.toMatchObject([
      { id: '1', participantId: 'user-1' },
    ]);
  });

  it('maps guest attendee ids to participant ids', async () => {
    mockedFetchJson.mockResolvedValue([transcriptResponse({ id: 2, attendeeId: 'guest:abc' })]);

    await expect(getMinutesTranscript('12')).resolves.toMatchObject([
      { id: '2', participantId: 'guest-abc' },
    ]);
  });

  it('uses a deterministic participant fallback when attendee id is null', async () => {
    mockedFetchJson.mockResolvedValue([transcriptResponse({ id: 3, attendeeId: null })]);

    await expect(getMinutesTranscript('12')).resolves.toMatchObject([
      { id: '3', participantId: 'transcript-3' },
    ]);
  });

  it('falls back speaker name from attendee id and then unknown label', async () => {
    mockedFetchJson.mockResolvedValue([
      transcriptResponse({ id: 4, attendeeId: 'account:user-4', speakerName: '' }),
      transcriptResponse({ id: 5, attendeeId: null, speakerName: null }),
    ]);

    await expect(getMinutesTranscript('12')).resolves.toMatchObject([
      { speakerName: 'account:user-4' },
      { speakerName: 'Không xác định' },
    ]);
  });

  it('preserves transcript text exactly', async () => {
    mockedFetchJson.mockResolvedValue([
      transcriptResponse({ id: 6, text: '  exact transcript text\nline two  ' }),
    ]);

    await expect(getMinutesTranscript('12')).resolves.toMatchObject([
      { text: '  exact transcript text\nline two  ' },
    ]);
  });

  it('uses absolute start time before segment time', async () => {
    mockedFetchJson.mockResolvedValue([
      transcriptResponse({
        id: 7,
        absoluteStartTime: '2026-09-08T08:00:03',
        segmentStartedAt: '2026-09-08T09:00:00',
        chunkStartMs: 10_000,
      }),
    ]);

    await expect(getMinutesTranscript('12')).resolves.toMatchObject([{ timestamp: '08:00:03' }]);
  });

  it('falls back from invalid absolute time to segment start plus chunk offset', async () => {
    mockedFetchJson.mockResolvedValue([
      transcriptResponse({
        id: 8,
        absoluteStartTime: 'not-a-date',
        segmentStartedAt: '2026-09-08T08:00:00',
        chunkStartMs: 3_000,
      }),
    ]);

    await expect(getMinutesTranscript('12')).resolves.toMatchObject([{ timestamp: '08:00:03' }]);
  });

  it('uses dash when transcript timing cannot be parsed', async () => {
    mockedFetchJson.mockResolvedValue([
      transcriptResponse({
        id: 9,
        absoluteStartTime: null,
        segmentStartedAt: 'invalid',
        chunkStartMs: 3_000,
      }),
    ]);

    await expect(getMinutesTranscript('12')).resolves.toMatchObject([{ timestamp: '-' }]);
  });

  it('includes real transcript result in minutes detail meeting', async () => {
    mockedGetMeetingDocuments.mockResolvedValue([]);
    mockedFetchJson
      .mockResolvedValueOnce({
        id: 12,
        meetingCode: 'MTG-12',
        title: 'Meeting',
        roomName: 'room-12',
        startTime: '2026-09-08T08:00:00',
        endTime: '2026-09-08T09:00:00',
        status: 'COMPLETED',
        accessType: 'PRIVATE',
        participants: [],
      })
      .mockResolvedValueOnce(minutesVersionResponse({ mainContent: 'A' }))
      .mockResolvedValueOnce([minutesVersionResponse({ mainContent: 'A' })])
      .mockResolvedValueOnce([
        transcriptResponse({
          id: 10,
          attendeeId: 'account:user-10',
          speakerName: 'Speaker Ten',
          text: 'real transcript',
        }),
      ]);

    const detail = await getMinutesDetail('12');

    expect(detail.meeting.transcript).toMatchObject([
      {
        id: '10',
        participantId: 'user-10',
        speakerName: 'Speaker Ten',
        text: 'real transcript',
      },
    ]);
  });

  it('keeps minutes detail transcript empty when the transcript API returns no entries', async () => {
    mockedGetMeetingDocuments.mockResolvedValue([]);
    mockedFetchJson
      .mockResolvedValueOnce({
        id: 12,
        meetingCode: 'MTG-12',
        title: 'Meeting',
        roomName: 'room-12',
        startTime: '2026-09-08T08:00:00',
        endTime: '2026-09-08T09:00:00',
        status: 'COMPLETED',
        accessType: 'PRIVATE',
        participants: [],
      })
      .mockResolvedValueOnce(minutesVersionResponse({ mainContent: 'A' }))
      .mockResolvedValueOnce([minutesVersionResponse({ mainContent: 'A' })])
      .mockResolvedValueOnce([]);

    const detail = await getMinutesDetail('12');

    expect(detail.meeting.transcript).toEqual([]);
  });
});

function transcriptResponse(
  overrides: Partial<{
    id: number;
    attendeeId: string | null;
    speakerName: string | null;
    absoluteStartTime: string | null;
    segmentStartedAt: string | null;
    chunkStartMs: number;
    text: string;
  }> = {},
) {
  return {
    id: 1,
    attendeeId: 'account:user-1',
    speakerName: 'Speaker One',
    absoluteStartTime: '2026-09-08T08:00:00',
    segmentStartedAt: null,
    chunkStartMs: 0,
    text: 'transcript text',
    ...overrides,
  };
}
