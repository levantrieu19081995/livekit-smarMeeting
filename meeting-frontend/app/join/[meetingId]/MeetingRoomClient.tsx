'use client';

import { LocalUserChoices } from '@livekit/components-react';
import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ManagementGuestJoin } from '@/components/management-room/ManagementGuestJoin';
import { ManagementPreJoin } from '@/components/management-room/ManagementPreJoin';
import { ManagementPointJoin } from '@/components/management-room/ManagementPointJoin';
import { ManagementPreJoinBackdrop } from '@/components/management-room/ManagementPreJoinBackdrop';
import { ManagementRoomError } from '@/components/management-room/ManagementRoomError';
import { ManagementRoomHeader } from '@/components/management-room/ManagementRoomHeader';
import { ManagementVideoConference } from '@/components/management-room/ManagementVideoConference';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import { storeGuestId } from '@/services/management/managementGuestIdentityService';
import {
  getMeetingJoinInfo,
  joinManagementMeeting,
} from '@/services/management/managementJoinService';
import { isRecoverableMediaDeviceError } from '@/services/management/managementMediaDeviceService';
import { ManagementApiError } from '@/services/management/httpClient';
import {
  readPointGuestResume,
  storePointGuestResume,
  clearPointGuestResume,
} from '@/services/management/pointGuestResume';
import {
  findSelectedMeetingPoint,
  readMeetingPointSelection,
  storeMeetingPointSelection,
} from '@/services/management/meetingPointSelection';
import type {
  ManagementCurrentUser,
  ManagementJoinResponse,
  ManagementMeetingJoinInfo,
} from '@/types/management/join';
import styles from '@/styles/management/ManagementRoom.module.css';
import { describeRoomDisconnect } from '@/services/management/roomDisconnect';
import type { DisconnectReason } from 'livekit-client';

type MeetingRoomState =
  | 'LOADING_MEETING'
  | 'CHECKING_SESSION'
  | 'AUTH_REQUIRED'
  | 'POINT_REQUIRED'
  | 'GUEST_NAME_REQUIRED'
  | 'PREJOIN'
  | 'JOINING'
  | 'CONNECTED'
  | 'ERROR';

interface MeetingRoomClientProps {
  meetingId: string;
}

export function MeetingRoomClient({ meetingId }: MeetingRoomClientProps) {
  const {
    loading: authLoading,
    authenticated,
    user,
    sessionUnavailable,
    refreshSession,
  } = useManagementAuth();
  const [state, setState] = useState<MeetingRoomState>('LOADING_MEETING');
  const [meeting, setMeeting] = useState<ManagementMeetingJoinInfo | null>(null);
  const [currentUser, setCurrentUser] = useState<ManagementCurrentUser | null>(null);
  const [joinData, setJoinData] = useState<ManagementJoinResponse | null>(null);
  const [userChoices, setUserChoices] = useState<LocalUserChoices | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [canRetryRoom, setCanRetryRoom] = useState(true);
  const [guestJoinError, setGuestJoinError] = useState<string | null>(null);
  const [pointJoinError, setPointJoinError] = useState<string | null>(null);
  const [pointId, setPointId] = useState<number | null>(null);
  const [pointConfirmed, setPointConfirmed] = useState(false);
  const generation = useRef(0);
  const active = useRef(false);
  const joiningGeneration = useRef<number | null>(null);
  const guestDisplayName = useRef<string | null>(null);
  const authScope = authLoading
    ? null
    : authenticated && user
      ? `account:${user.publicId}`
      : 'guest';
  const latestAuthScope = useRef<string | null>(authScope);
  const previousAuthScope = useRef<string | null>(null);
  latestAuthScope.current = authScope;

  const loadRoom = useCallback(async () => {
    const requestGeneration = ++generation.current;
    joiningGeneration.current = null;
    try {
      setState('LOADING_MEETING');
      setError(null);
      setCanRetryRoom(true);
      setGuestJoinError(null);
      setJoinData(null);
      setUserChoices(null);
      setCurrentUser(null);
      setMeeting(null);
      setPointJoinError(null);
      setPointId(null);
      setPointConfirmed(false);
      guestDisplayName.current = null;

      const resolvedMeeting = await getMeetingJoinInfo(meetingId);
      if (!active.current || generation.current !== requestGeneration) return;
      setMeeting(resolvedMeeting);
      const storedPoint = findSelectedMeetingPoint(
        resolvedMeeting,
        readMeetingPointSelection(meetingId),
      );
      setPointId(storedPoint?.id ?? null);
      if (!storedPoint) storeMeetingPointSelection(meetingId, null);
      setState('CHECKING_SESSION');
    } catch (loadError) {
      if (!active.current || generation.current !== requestGeneration) return;
      setError(loadError instanceof Error ? loadError.message : 'Khong the chuan bi phong hop.');
      setState('ERROR');
    }
  }, [meetingId]);

  useEffect(() => {
    active.current = true;
    void loadRoom();
    return () => {
      active.current = false;
      generation.current += 1;
    };
  }, [loadRoom]);

  useEffect(() => {
    const previousScope = previousAuthScope.current;
    previousAuthScope.current = authScope;
    // Rechecking an established session invalidates pending tokens as well.
    if (authScope == null) {
      if (previousScope != null) void loadRoom();
      return;
    }
    if (previousScope != null && previousScope !== authScope) void loadRoom();
  }, [authScope, loadRoom]);

  const requestJoin = useCallback(
    async (identity: ManagementCurrentUser) => {
      const requestGeneration = generation.current;
      if (joiningGeneration.current === requestGeneration || !meeting) return;
      const requestAuthScope = latestAuthScope.current;
      if (
        requestAuthScope !==
        (identity.kind === 'AUTHENTICATED' ? `account:${identity.publicId}` : 'guest')
      )
        return;
      const selectedPoint = findSelectedMeetingPoint(meeting, pointId);
      if (meeting.creationMode === 'POINT' && (!selectedPoint || !pointConfirmed)) {
        setState('POINT_REQUIRED');
        return;
      }
      joiningGeneration.current = requestGeneration;
      try {
        setState('JOINING');
        setGuestJoinError(null);
        setPointJoinError(null);
        if (identity.kind === 'AUTHENTICATED') setCurrentUser(identity);
        const pointGuestResume =
          identity.kind === 'GUEST' ? readPointGuestResume(meetingId) : undefined;
        const resolvedJoin = await joinManagementMeeting(meetingId, {
          participantType: identity.kind,
          guestId: identity.kind === 'GUEST' ? pointGuestResume?.guestId : undefined,
          guestResumeToken: pointGuestResume?.guestResumeToken,
          displayName: identity.name,
          ...(selectedPoint ? { pointId: selectedPoint.id } : {}),
        });
        if (
          !active.current ||
          generation.current !== requestGeneration ||
          latestAuthScope.current !== requestAuthScope
        )
          return;
        if (identity.kind === 'GUEST') {
          storePointGuestResume(
            meetingId,
            resolvedJoin.participant.attendeeId,
            resolvedJoin.participant.chatWriteToken,
          );
          try {
            storeGuestId(
              meetingId,
              resolvedJoin.participant.attendeeId.replace('guest:', 'guest-'),
            );
          } catch {
            // Storage is optional; a token that was issued successfully must remain usable.
          }
        }
        setJoinData(resolvedJoin);
        setCurrentUser({
          ...identity,
          attendeeId: resolvedJoin.participant.attendeeId,
          name: resolvedJoin.participant.displayName,
        });
        setState('PREJOIN');
      } catch (joinError) {
        if (
          !active.current ||
          generation.current !== requestGeneration ||
          latestAuthScope.current !== requestAuthScope
        )
          return;
        if (
          joinError instanceof ManagementApiError &&
          ['MEETING_POINT_REQUIRED', 'MEETING_POINT_INVALID'].includes(joinError.code ?? '')
        ) {
          storeMeetingPointSelection(meetingId, null);
          setPointId(null);
          setPointConfirmed(false);
          setPointJoinError(joinError.message);
          try {
            const refreshedMeeting = await getMeetingJoinInfo(meetingId);
            if (
              !active.current ||
              generation.current !== requestGeneration ||
              latestAuthScope.current !== requestAuthScope
            )
              return;
            setMeeting(refreshedMeeting);
            setState(
              refreshedMeeting.creationMode === 'POINT' ? 'POINT_REQUIRED' : 'CHECKING_SESSION',
            );
          } catch (refreshError) {
            if (
              !active.current ||
              generation.current !== requestGeneration ||
              latestAuthScope.current !== requestAuthScope
            )
              return;
            setError(
              refreshError instanceof Error
                ? refreshError.message
                : 'Không thể tải danh sách điểm cầu.',
            );
            setState('ERROR');
          }
        } else if (identity.kind === 'GUEST') {
          setCurrentUser(null);
          const invalidResume =
            joinError instanceof ManagementApiError &&
            ['SPEAKING_INVALID_RESUME_TOKEN', 'MEETING_INVALID_RESUME_TOKEN'].includes(
              joinError.code ?? '',
            );
          if (invalidResume) clearPointGuestResume(meetingId);
          setGuestJoinError(
            invalidResume
              ? 'Phiên khách trước không còn hợp lệ. Vui lòng nhấn Tiếp tục để tham gia bằng phiên mới.'
              : joinError instanceof Error
                ? joinError.message
                : 'Không thể chuẩn bị phòng họp cho khách.',
          );
          setState('GUEST_NAME_REQUIRED');
        } else {
          setError(joinError instanceof Error ? joinError.message : 'Không thể tham gia cuộc họp.');
          setState('ERROR');
        }
      } finally {
        if (joiningGeneration.current === requestGeneration) joiningGeneration.current = null;
      }
    },
    [meeting, meetingId, pointId, pointConfirmed],
  );

  useEffect(() => {
    if (!meeting || authLoading || sessionUnavailable || state !== 'CHECKING_SESSION') return;
    if (
      meeting.creationMode === 'POINT' &&
      (!pointConfirmed || !findSelectedMeetingPoint(meeting, pointId))
    ) {
      setState('POINT_REQUIRED');
      return;
    }
    if (authenticated && user) {
      void requestJoin({
        attendeeId: `account:${user.publicId}`,
        publicId: user.publicId,
        name: user.fullName,
        email: user.email,
        kind: 'AUTHENTICATED',
        systemRole: user.systemRole,
      });
    } else if (meeting.accessType === 'PRIVATE') {
      setState('AUTH_REQUIRED');
    } else if (guestDisplayName.current) {
      void requestJoin({ attendeeId: '', name: guestDisplayName.current, kind: 'GUEST' });
    } else {
      setState('GUEST_NAME_REQUIRED');
    }
  }, [
    authLoading,
    authenticated,
    sessionUnavailable,
    meeting,
    pointId,
    pointConfirmed,
    requestJoin,
    state,
    user,
  ]);

  const handleGuestIdentitySubmit = useCallback(
    async (displayName: string) => {
      guestDisplayName.current = displayName;
      await requestJoin({ attendeeId: '', name: displayName, kind: 'GUEST' });
    },
    [requestJoin],
  );

  const handlePreJoinError = useCallback((roomError: Error) => {
    if (!isRecoverableMediaDeviceError(roomError)) {
      setError(roomError.message);
      setState('ERROR');
    }
  }, []);

  const handleRoomError = useCallback((roomError: Error) => {
    if (!isRecoverableMediaDeviceError(roomError)) {
      setError(roomError.message);
      setState('ERROR');
    }
  }, []);

  const handleRoomDisconnected = useCallback((reason?: DisconnectReason) => {
    const result = describeRoomDisconnect(reason);
    setCanRetryRoom(result.retry);
    setError(result.message);
    setState('ERROR');
  }, []);

  if (sessionUnavailable && !authenticated && !userChoices) {
    return (
      <ManagementRoomError
        meetingId={meetingId}
        message="Chưa thể xác nhận phiên đăng nhập. Kiểm tra kết nối rồi thử lại."
        onRetry={() => void refreshSession()}
      />
    );
  }
  if (state === 'ERROR') {
    return (
      <ManagementRoomError
        meetingId={meetingId}
        message={error || 'Khong the tham gia cuoc hop.'}
        onRetry={canRetryRoom ? () => void loadRoom() : undefined}
      />
    );
  }

  if (!meeting) {
    return (
      <div className={`${styles.page} ${styles.loadingPage}`}>
        <div className={styles.loading} role="status">
          <h2 className={styles.loadingTitle}>Đang chuẩn bị cuộc họp</h2>
          <p className={styles.loadingText}>Hệ thống đang lấy thông tin tham gia</p>
        </div>
      </div>
    );
  }

  if (state === 'GUEST_NAME_REQUIRED' || (state === 'JOINING' && !currentUser)) {
    return (
      <ManagementGuestJoin
        meeting={meeting}
        submitting={state === 'JOINING'}
        error={guestJoinError}
        onSubmit={handleGuestIdentitySubmit}
      />
    );
  }

  if (state === 'POINT_REQUIRED') {
    return (
      <ManagementPointJoin
        meeting={meeting}
        error={pointJoinError}
        initialPointId={pointId}
        onRefresh={() => void loadRoom()}
        onSubmit={(selectedId) => {
          if (!findSelectedMeetingPoint(meeting, selectedId)) return;
          storeMeetingPointSelection(meetingId, selectedId);
          setPointId(selectedId);
          setPointConfirmed(true);
          setState('CHECKING_SESSION');
        }}
      />
    );
  }

  if (state === 'AUTH_REQUIRED') {
    return (
      <div className={styles.page}>
        <div className={styles.shell}>
          <div className={styles.authRequiredCard}>
            <p className={styles.eyebrow}>Yêu cầu xác thực</p>
            <h2 className={styles.guestJoinTitle}>Cuộc họp riêng tư</h2>
            <p className={styles.guestJoinText}>
              Bạn cần đăng nhập bằng tài khoản đã được mời để tham gia cuộc họp này.
            </p>
            <div className={styles.inlineActionGroup}>
              <Link
                href={`/auth/login?returnTo=/join/${meeting.id}`}
                className={styles.guestSubmit}
              >
                Đăng nhập
              </Link>
              <Link
                href={`/auth/register?returnTo=/join/${meeting.id}`}
                className={styles.secondaryButton}
              >
                Đăng ký
              </Link>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (!currentUser || !joinData) {
    return (
      <div className={`${styles.page} ${styles.loadingPage}`}>
        <div className={styles.loading} role="status">
          <h2 className={styles.loadingTitle}>Đang chuẩn bị cuộc họp</h2>
          <p className={styles.loadingText}>Hệ thống đang xác nhận thông tin tham gia</p>
        </div>
      </div>
    );
  }

  const canManageDocuments =
    currentUser.kind === 'AUTHENTICATED' && joinData.permissions.canManageParticipants;

  return (
    <div className={`${styles.page} ${userChoices ? styles.connectedPage : styles.preJoinPage}`}>
      {!userChoices ? <ManagementPreJoinBackdrop /> : null}
      <ManagementRoomHeader meeting={meeting} currentUser={currentUser} />
      <div className={styles.shell}>
        {userChoices ? (
          <ManagementVideoConference
            meetingId={meetingId}
            join={joinData}
            creationMode={meeting.creationMode}
            userChoices={userChoices}
            canManageDocuments={canManageDocuments}
            onError={handleRoomError}
            onDisconnected={handleRoomDisconnected}
          />
        ) : (
          <ManagementPreJoin
            currentUser={currentUser}
            onSubmit={(choices) => {
              setUserChoices(choices);
              setState('CONNECTED');
            }}
            onError={handlePreJoinError}
          />
        )}
      </div>
    </div>
  );
}
