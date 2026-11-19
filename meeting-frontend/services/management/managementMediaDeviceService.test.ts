import { afterEach, describe, expect, it, vi } from 'vitest';
import type { LocalUserChoices } from '@livekit/components-react';
import {
  getManagementMediaContextError,
  isRecoverableMediaDeviceError,
  normalizeMediaDeviceChoices,
} from './managementMediaDeviceService';

function createChoices(overrides: Partial<LocalUserChoices> = {}): LocalUserChoices {
  return {
    username: 'CUSC Admin',
    audioEnabled: true,
    videoEnabled: true,
    audioDeviceId: '',
    videoDeviceId: '',
    ...overrides,
  };
}

function createDevice(kind: MediaDeviceKind, deviceId: string): MediaDeviceInfo {
  return {
    kind,
    deviceId,
    groupId: '',
    label: deviceId,
    toJSON: () => ({}),
  };
}

describe('managementMediaDeviceService', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('turns video off when no camera exists while keeping audio enabled', () => {
    const result = normalizeMediaDeviceChoices(createChoices(), [
      createDevice('audioinput', 'mic-1'),
    ]);

    expect(result.choices.videoEnabled).toBe(false);
    expect(result.choices.audioEnabled).toBe(true);
    expect(result.warning).toContain('Không tìm thấy camera');
  });

  it('clears stale device ids so LiveKit can use default devices', () => {
    const result = normalizeMediaDeviceChoices(
      createChoices({
        audioDeviceId: 'old-mic',
        videoDeviceId: 'old-camera',
      }),
      [createDevice('audioinput', 'mic-1'), createDevice('videoinput', 'camera-1')],
    );

    expect(result.choices.audioDeviceId).toBe('');
    expect(result.choices.videoDeviceId).toBe('');
  });

  it('keeps valid device ids unchanged', () => {
    const result = normalizeMediaDeviceChoices(
      createChoices({
        audioDeviceId: 'mic-1',
        videoDeviceId: 'camera-1',
      }),
      [createDevice('audioinput', 'mic-1'), createDevice('videoinput', 'camera-1')],
    );

    expect(result.choices.audioDeviceId).toBe('mic-1');
    expect(result.choices.videoDeviceId).toBe('camera-1');
  });

  it('treats requested device not found as recoverable', () => {
    expect(isRecoverableMediaDeviceError(new Error('Requested device not found'))).toBe(true);
  });

  it('reports that microphone requires a secure context on LAN HTTP', () => {
    vi.stubGlobal('window', {
      isSecureContext: false,
      location: {
        origin: 'http://172.16.1.229:3000',
      },
    });

    vi.stubGlobal('navigator', {
      mediaDevices: undefined,
    });

    expect(getManagementMediaContextError()).toContain('HTTP bằng IP');
  });
});
