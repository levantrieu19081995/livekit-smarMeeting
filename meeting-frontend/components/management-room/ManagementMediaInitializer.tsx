'use client';

import { useRoomContext } from '@livekit/components-react';
import type { LocalUserChoices } from '@livekit/components-react';
import type { AudioCaptureOptions, VideoCaptureOptions } from 'livekit-client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ManagementMediaNotice } from '@/components/management-room/ManagementMediaNotice';
import {
  getMediaDeviceErrorMessage,
  isRecoverableMediaDeviceError,
} from '@/services/management/managementMediaDeviceService';
import styles from '@/styles/management/ManagementRoom.module.css';

interface ManagementMediaInitializerProps {
  connected: boolean;
  userChoices: LocalUserChoices;
  deviceWarning?: string | null;
}

type MediaStatus = 'idle' | 'starting' | 'ready' | 'error';

function audioOptions(deviceId?: string): AudioCaptureOptions {
  return {
    deviceId: deviceId || undefined,
    echoCancellation: true,
    noiseSuppression: true,
    autoGainControl: true,
  };
}

function videoOptions(deviceId?: string): VideoCaptureOptions {
  return {
    deviceId: deviceId || undefined,
  };
}

export function ManagementMediaInitializer({
  connected,
  userChoices,
  deviceWarning,
}: ManagementMediaInitializerProps) {
  const room = useRoomContext();
  const initializedRef = useRef(false);
  const statusPanelRef = useRef<HTMLDivElement>(null);
  const [noticeDock, setNoticeDock] = useState<HTMLDivElement | null>(null);
  const [status, setStatus] = useState<MediaStatus>('idle');
  const [trackSid, setTrackSid] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [cameraWarning, setCameraWarning] = useState<string | null>(null);

  useEffect(() => {
    const panel = statusPanelRef.current;
    const container = panel?.parentElement;
    const toolbar = container?.querySelector('.lk-control-bar');
    if (!panel || !container || !toolbar || !noticeDock) return;

    function fitAboveToolbar() {
      if (!panel || !toolbar || !container || !noticeDock) return;
      const boundary = toolbar.getClientRects().length
        ? toolbar.getBoundingClientRect().top
        : container.getBoundingClientRect().bottom;
      noticeDock.style.bottom = `${Math.max(0, container.getBoundingClientRect().bottom - boundary) + 56}px`;
      const noticeBoundary = noticeDock.childElementCount
        ? Math.min(boundary, noticeDock.getBoundingClientRect().top)
        : boundary;
      const availableHeight = noticeBoundary - panel.getBoundingClientRect().top;
      panel.style.maxHeight = `${Math.max(0, availableHeight - 12)}px`;
    }

    fitAboveToolbar();
    const observer = new ResizeObserver(fitAboveToolbar);
    observer.observe(container);
    observer.observe(toolbar);
    observer.observe(noticeDock);
    return () => observer.disconnect();
  }, [connected, deviceWarning, noticeDock]);

  const startCamera = useCallback(async () => {
    if (!userChoices.videoEnabled) {
      return;
    }

    try {
      await room.localParticipant.setCameraEnabled(true, videoOptions(userChoices.videoDeviceId));
      setCameraWarning(null);
    } catch (error) {
      setCameraWarning(getMediaDeviceErrorMessage(error, 'videoinput'));
    }
  }, [room.localParticipant, userChoices.videoDeviceId, userChoices.videoEnabled]);

  const startMicrophone = useCallback(async () => {
    if (!userChoices.audioEnabled) {
      setStatus('idle');
      setMessage('Microphone đang tắt theo lựa chọn của bạn.');
      await startCamera();
      return;
    }

    setStatus('starting');
    setMessage('Đang khởi động microphone...');

    try {
      let publication = await room.localParticipant.setMicrophoneEnabled(
        true,
        audioOptions(userChoices.audioDeviceId),
      );

      if (!publication && userChoices.audioDeviceId) {
        publication = await room.localParticipant.setMicrophoneEnabled(true, audioOptions());
      }

      setTrackSid(publication?.trackSid ?? null);
      setStatus('ready');
      setMessage(null);
      await startCamera();
    } catch (error) {
      if (userChoices.audioDeviceId && isRecoverableMediaDeviceError(error)) {
        try {
          const publication = await room.localParticipant.setMicrophoneEnabled(
            true,
            audioOptions(),
          );
          setTrackSid(publication?.trackSid ?? null);
          setStatus('ready');
          setMessage(null);
          await startCamera();
          return;
        } catch (retryError) {
          setStatus('error');
          setMessage(getMediaDeviceErrorMessage(retryError, 'audioinput'));
          return;
        }
      }

      setStatus('error');
      setMessage(getMediaDeviceErrorMessage(error, 'audioinput'));
      await startCamera();
    }
  }, [room.localParticipant, startCamera, userChoices.audioDeviceId, userChoices.audioEnabled]);

  useEffect(() => {
    if (!connected || initializedRef.current) {
      return;
    }

    initializedRef.current = true;
    void startMicrophone();
  }, [connected, startMicrophone]);

  function handleRetry() {
    initializedRef.current = true;
    void startMicrophone();
  }

  if (!connected && status === 'idle' && !deviceWarning) {
    return null;
  }

  return (
    <>
      <div ref={statusPanelRef} className={styles.mediaStatusPanel}>
        {status === 'starting' ? (
          <div className={styles.mediaStatusItem}>
            <strong>Đang khởi động âm thanh</strong>
            <p>{message}</p>
          </div>
        ) : null}

        {status === 'ready' ? (
          <ManagementMediaNotice
            title="Âm thanh đã sẵn sàng"
            kind="microphone"
            tone="success"
            collapsedContainer={noticeDock}
          >
            <p>Microphone đang phát âm thanh lên LiveKit. Hệ thống đã sẵn sàng lấy AudioFrame.</p>
            {trackSid ? <code>Audio Track SID: {trackSid}</code> : null}
          </ManagementMediaNotice>
        ) : null}

        {status === 'error' ? (
          <ManagementMediaNotice
            key={`microphone:${message}`}
            title="Microphone chưa bật được"
            kind="microphone"
            collapsedContainer={noticeDock}
          >
            <p>{message}</p>
            <button type="button" className={styles.retryButton} onClick={handleRetry}>
              Thử lại microphone
            </button>
          </ManagementMediaNotice>
        ) : null}

        {cameraWarning ? (
          <ManagementMediaNotice
            key={`camera:${cameraWarning}`}
            title="Camera chưa sẵn sàng"
            kind="camera"
            collapsedContainer={noticeDock}
          >
            <p>{cameraWarning}</p>
          </ManagementMediaNotice>
        ) : null}
        {deviceWarning ? (
          <ManagementMediaNotice
            key={`device:${deviceWarning}`}
            title="Thiết bị chưa sẵn sàng"
            kind="device"
            collapsedContainer={noticeDock}
          >
            <p>{deviceWarning}</p>
          </ManagementMediaNotice>
        ) : null}
      </div>
      <div
        ref={setNoticeDock}
        className={styles.mediaNoticeDock}
        role="group"
        aria-label="Thông báo thiết bị đã thu gọn"
      />
    </>
  );
}
