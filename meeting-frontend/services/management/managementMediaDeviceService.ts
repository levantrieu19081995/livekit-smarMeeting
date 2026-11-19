import type { LocalUserChoices } from '@livekit/components-react';
import { MediaDeviceFailure } from 'livekit-client';

export interface NormalizedMediaChoices {
  choices: LocalUserChoices;
  warning?: string;
}

const RECOVERABLE_MEDIA_MESSAGES = [
  'requested device not found',
  'device not found',
  'permission denied',
  'permission dismissed',
  'device in use',
  'could not start audio source',
  'could not start video source',
];

export function isRecoverableMediaDeviceError(error: unknown) {
  if (MediaDeviceFailure.getFailure(error) != null) {
    return true;
  }

  const message = error instanceof Error ? error.message : String(error ?? '');
  const normalizedMessage = message.toLowerCase();
  return RECOVERABLE_MEDIA_MESSAGES.some((recoverableMessage) =>
    normalizedMessage.includes(recoverableMessage),
  );
}

export function getMediaDeviceErrorMessage(error: unknown, kind?: MediaDeviceKind) {
  const failure = MediaDeviceFailure.getFailure(error);

  if (failure === MediaDeviceFailure.PermissionDenied) {
    return kind === 'videoinput'
      ? 'Trình duyệt chưa cho phép dùng camera. Bạn vẫn có thể ở trong phòng và dùng microphone.'
      : 'Trình duyệt chưa cho phép dùng microphone. Hãy cấp quyền microphone rồi thử lại.';
  }

  if (failure === MediaDeviceFailure.NotFound) {
    return kind === 'videoinput'
      ? 'Không tìm thấy camera. Bạn vẫn có thể vào phòng và nói chuyện bằng microphone.'
      : 'Không tìm thấy microphone. Hãy kiểm tra thiết bị âm thanh rồi thử lại.';
  }

  if (failure === MediaDeviceFailure.DeviceInUse) {
    return kind === 'videoinput'
      ? 'Camera đang được ứng dụng khác sử dụng. Bạn vẫn có thể tiếp tục bằng microphone.'
      : 'Microphone đang được ứng dụng khác sử dụng. Hãy đóng ứng dụng đang dùng microphone rồi thử lại.';
  }

  if (kind === 'videoinput') {
    return 'Camera chưa sẵn sàng. Bạn vẫn có thể ở trong phòng và dùng microphone.';
  }

  if (kind === 'audioinput') {
    return 'Microphone chưa sẵn sàng. Hãy kiểm tra thiết bị âm thanh rồi thử lại.';
  }

  return 'Thiết bị âm thanh hoặc hình ảnh chưa sẵn sàng. Bạn vẫn có thể tiếp tục trong phòng.';
}

export function normalizeMediaDeviceChoices(
  choices: LocalUserChoices,
  devices: MediaDeviceInfo[],
): NormalizedMediaChoices {
  const audioInputs = devices.filter((device) => device.kind === 'audioinput');
  const videoInputs = devices.filter((device) => device.kind === 'videoinput');
  const hasDeviceList = devices.length > 0;

  const normalizedChoices: LocalUserChoices = { ...choices };
  const warnings: string[] = [];

  if (
    normalizedChoices.audioDeviceId &&
    audioInputs.length > 0 &&
    !audioInputs.some((device) => device.deviceId === normalizedChoices.audioDeviceId)
  ) {
    normalizedChoices.audioDeviceId = '';
  }

  if (
    normalizedChoices.videoDeviceId &&
    videoInputs.length > 0 &&
    !videoInputs.some((device) => device.deviceId === normalizedChoices.videoDeviceId)
  ) {
    normalizedChoices.videoDeviceId = '';
  }

  if (hasDeviceList && videoInputs.length === 0) {
    normalizedChoices.videoEnabled = false;
    warnings.push(
      'Không tìm thấy camera. Bạn vẫn có thể vào phòng và nói chuyện bằng microphone.',
    );
  }

  return {
    choices: normalizedChoices,
    warning: warnings[0],
  };
}

export async function normalizeCurrentMediaDeviceChoices(
  choices: LocalUserChoices,
): Promise<NormalizedMediaChoices> {
  if (typeof navigator === 'undefined' || !navigator.mediaDevices?.enumerateDevices) {
    return { choices };
  }

  try {
    const devices = await navigator.mediaDevices.enumerateDevices();
    return normalizeMediaDeviceChoices(choices, devices);
  } catch (error) {
    return {
      choices,
      warning: getMediaDeviceErrorMessage(error),
    };
  }
}

export function getManagementMediaContextError(): string | undefined {
  if (typeof window === 'undefined') {
    return undefined;
  }

  if (
    window.isSecureContext &&
    typeof navigator.mediaDevices?.getUserMedia === 'function'
  ) {
    return undefined;
  }

  return [
    'Trình duyệt không cho phép dùng microphone trên địa chỉ HTTP bằng IP.',
    'Hãy mở phòng bằng HTTPS hoặc localhost.',
    `Địa chỉ hiện tại: ${window.location.origin}`,
  ].join(' ');
}
