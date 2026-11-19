'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ManagementRoomToolbarIcon } from '@/components/management-room/ManagementRoomToolbarIcon';
import {
  getManagementCaptureStatus,
  startManagementCapture,
  stopManagementCapture,
} from '@/services/management/managementCaptureService';
import type {
  ManagementCaptureResponse,
  ManagementCaptureStatus,
} from '@/types/management/capture';
import styles from '@/styles/management/ManagementRoom.module.css';
import {
  ManagementRequestCoordinator,
  withManagementDeadline,
} from '@/services/management/managementRequestCoordinator';
import { QnaRefreshScheduler } from '@/services/management/qnaRefreshScheduler';

interface ManagementCaptureControlProps {
  meetingId: string;
  canControl: boolean;
  roomRoot: HTMLElement | null;
}

type PendingAction = 'START' | 'STOP' | null;

function buttonLabel(
  status: ManagementCaptureStatus,
  pendingAction: PendingAction,
  retryingStop: boolean,
  completedNotice: boolean,
) {
  if (pendingAction === 'START' || status === 'STARTING') return 'Đang bắt đầu...';
  if (pendingAction === 'STOP') return 'Đang kết thúc...';
  if (status === 'STOPPING') return 'Đang kết thúc...';
  if (status === 'RUNNING') return 'Kết thúc ghi BB';
  if (status === 'COMPLETED') {
    return completedNotice ? 'Đã kết thúc ghi BB' : 'Bắt đầu ghi BB';
  }
  if (status === 'FAILED') return retryingStop ? 'Thử kết thúc ghi BB' : 'Thử lại ghi BB';
  return 'Bắt đầu ghi BB';
}

export function ManagementCaptureControl({
  meetingId,
  canControl,
  roomRoot,
}: ManagementCaptureControlProps) {
  const [portalTarget, setPortalTarget] = useState<HTMLDivElement | null>(null);
  const [capture, setCapture] = useState<ManagementCaptureResponse | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingAction>(null);
  const [completedNotice, setCompletedNotice] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const completedTimer = useRef<number | null>(null);
  const reads = useRef(new ManagementRequestCoordinator());
  const scheduler = useRef<QnaRefreshScheduler | null>(null);
  const write = useRef<AbortController | null>(null);
  const busy = useRef(false);
  const scope = useRef(0);
  const active = useRef(false);

  const refresh = useCallback(async () => {
    if (busy.current) return;
    await reads.current.run(
      (signal) => getManagementCaptureStatus(meetingId, signal),
      (response) => {
        setCapture(response);
        setError(response.errorMessage);
      },
      (refreshError) => {
        setError(
          refreshError instanceof Error
            ? refreshError.message
            : 'Không thể đọc trạng thái ghi biên bản.',
        );
      },
    );
  }, [meetingId]);

  useEffect(() => {
    if (!canControl || !roomRoot) return;

    const controlBar = roomRoot.querySelector<HTMLElement>('.lk-control-bar');
    const leaveButton = controlBar?.querySelector<HTMLElement>('.lk-disconnect-button');
    if (!controlBar || !leaveButton) return;

    const target = document.createElement('div');
    target.className = styles.captureControlMount;
    controlBar.insertBefore(target, leaveButton);
    setPortalTarget(target);

    return () => {
      setPortalTarget(null);
      target.remove();
    };
  }, [canControl, roomRoot]);

  useEffect(() => {
    if (!canControl) return;
    active.current = true;
    scope.current++;
    busy.current = false;
    setPendingAction(null);
    setCapture(null);
    const coordinator = reads.current;
    const engine = new QnaRefreshScheduler(
      refresh,
      () => !busy.current && document.visibilityState !== 'hidden',
    );
    scheduler.current = engine;
    engine.request();
    const interval = window.setInterval(() => engine.poll(), 3000);
    const wake = () => engine.request();
    window.addEventListener('online', wake);
    document.addEventListener('visibilitychange', wake);
    return () => {
      active.current = false;
      scope.current++;
      coordinator.invalidate();
      write.current?.abort();
      engine.dispose();
      scheduler.current = null;
      window.clearInterval(interval);
      window.removeEventListener('online', wake);
      document.removeEventListener('visibilitychange', wake);
    };
  }, [canControl, refresh]);

  useEffect(
    () => () => {
      if (completedTimer.current != null) {
        window.clearTimeout(completedTimer.current);
      }
    },
    [],
  );

  async function handleCaptureClick() {
    if (!capture || busy.current) return;
    busy.current = true;
    const requestScope = scope.current;
    reads.current.invalidate();
    const controller = new AbortController();
    write.current = controller;

    const shouldStop =
      capture.status === 'RUNNING' ||
      (capture.status === 'FAILED' && Boolean(capture.externalSessionId));
    setPendingAction(shouldStop ? 'STOP' : 'START');
    setCompletedNotice(false);
    setError(null);
    try {
      const response = await withManagementDeadline(
        (signal) =>
          shouldStop
            ? stopManagementCapture(meetingId, signal)
            : startManagementCapture(meetingId, signal),
        controller,
      );
      if (!active.current || scope.current !== requestScope) return;
      setCapture(response);
      setError(response.errorMessage);
      if (shouldStop && response.status === 'COMPLETED') {
        setCompletedNotice(true);
        if (completedTimer.current != null) {
          window.clearTimeout(completedTimer.current);
        }
        completedTimer.current = window.setTimeout(() => {
          setCompletedNotice(false);
          completedTimer.current = null;
        }, 2500);
      }
    } catch (captureError) {
      if (!active.current || scope.current !== requestScope) return;
      setError(
        captureError instanceof Error
          ? captureError.message
          : 'Không thể thay đổi trạng thái ghi biên bản.',
      );
    } finally {
      if (active.current && scope.current === requestScope) {
        busy.current = false;
        write.current = null;
        setPendingAction(null);
        scheduler.current?.request();
      }
    }
  }

  if (!canControl || !portalTarget) return null;

  const status = capture?.status ?? 'NOT_STARTED';
  const retryingStop = status === 'FAILED' && Boolean(capture?.externalSessionId);
  const recording = status === 'RUNNING' || retryingStop;
  const disabled =
    !capture ||
    pendingAction != null ||
    status === 'STARTING' ||
    status === 'STOPPING' ||
    (status === 'COMPLETED' && completedNotice);

  return createPortal(
    <button
      type="button"
      className={`lk-button ${styles.captureButton} ${recording ? styles.captureButtonRecording : styles.captureButtonStart}`}
      onClick={() => void handleCaptureClick()}
      disabled={disabled}
      aria-busy={pendingAction != null || status === 'STARTING' || status === 'STOPPING'}
      aria-label={buttonLabel(status, pendingAction, retryingStop, completedNotice)}
      title={error ?? buttonLabel(status, pendingAction, retryingStop, completedNotice)}
    >
      <ManagementRoomToolbarIcon
        name={recording ? 'stopRecording' : 'record'}
        className={styles.captureIndicator}
      />
      <span className={styles.roomToolbarLabel}>
        {buttonLabel(status, pendingAction, retryingStop, completedNotice)}
      </span>
    </button>,
    portalTarget,
  );
}
