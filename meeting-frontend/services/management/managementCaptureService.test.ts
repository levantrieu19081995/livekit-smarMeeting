import { beforeEach, describe, expect, it, vi } from 'vitest';
import { managementFetchJson } from '@/services/management/httpClient';
import {
  getManagementCaptureStatus,
  getManagementCaptureSessions,
  startManagementCapture,
  stopManagementCapture,
} from './managementCaptureService';

vi.mock('@/services/management/httpClient', () => ({
  managementFetchJson: vi.fn(),
}));

const mockedFetchJson = vi.mocked(managementFetchJson);

describe('managementCaptureService', () => {
  beforeEach(() => mockedFetchJson.mockReset());

  it('gets the capture status', async () => {
    await getManagementCaptureStatus('7');
    expect(mockedFetchJson).toHaveBeenCalledWith('/api/meetings/7/capture/status');
  });

  it('gets all capture sessions in timeline order', async () => {
    await getManagementCaptureSessions('7');
    expect(mockedFetchJson).toHaveBeenCalledWith('/api/meetings/7/capture/sessions');
  });

  it('starts capture with a CSRF-protected POST', async () => {
    await startManagementCapture('7');
    expect(mockedFetchJson).toHaveBeenCalledWith('/api/meetings/7/capture/start', {
      method: 'POST',
    });
  });

  it('stops capture with a CSRF-protected POST', async () => {
    await stopManagementCapture('7');
    expect(mockedFetchJson).toHaveBeenCalledWith('/api/meetings/7/capture/stop', {
      method: 'POST',
    });
  });
});
