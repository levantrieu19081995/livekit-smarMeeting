'use client';

import { LiveKitRoom } from '@livekit/components-react';
import type { LocalUserChoices } from '@livekit/components-react';
import { DisconnectReason } from 'livekit-client';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import toast from 'react-hot-toast';
import { MeetingDocumentsPanel } from '@/components/management/documents/MeetingDocumentsPanel';
import { MeetingPollPanel } from '@/components/management/polls/MeetingPollPanel';
import { MeetingQnaPanel } from '@/components/management/qna/MeetingQnaPanel';
import { MeetingSpeakingPanel } from '@/components/management/speaking/MeetingSpeakingPanel';
import { ManagementSpeakingBridge } from '@/components/management-room/ManagementSpeakingBridge';
import { ManagementPollBridge } from '@/components/management-room/ManagementPollBridge';
import { ManagementQnaBridge } from '@/components/management-room/ManagementQnaBridge';
import { ManagementConferenceLayout } from '@/components/management-room/ManagementConferenceLayout';
import { ManagementDocumentsBridge } from '@/components/management-room/ManagementDocumentsBridge';
import { ManagementMediaInitializer } from '@/components/management-room/ManagementMediaInitializer';
import { ManagementCaptureControl } from '@/components/management-room/ManagementCaptureControl';
import { ManagementToolbarVisibility } from '@/components/management-room/ManagementToolbarVisibility';
import { ManagementRoomToolbarIcon } from '@/components/management-room/ManagementRoomToolbarIcon';
import { endManagementMeeting } from '@/services/management/managementMeetingApi';
import {
  getMediaDeviceErrorMessage,
  isRecoverableMediaDeviceError,
} from '@/services/management/managementMediaDeviceService';
import type { ManagementJoinResponse } from '@/types/management/join';
import type { ManagementMeetingCreationMode } from '@/types/management/meeting';
import styles from '@/styles/management/ManagementRoom.module.css';

interface ManagementVideoConferenceProps {
  meetingId: string;
  join: ManagementJoinResponse;
  userChoices: LocalUserChoices;
  canManageDocuments: boolean;
  creationMode?: ManagementMeetingCreationMode;
  onError: (error: Error) => void;
  onDisconnected: (reason?: DisconnectReason) => void;
}

export function ManagementVideoConference({
  meetingId,
  join,
  userChoices,
  canManageDocuments,
  creationMode,
  onError,
  onDisconnected,
}: ManagementVideoConferenceProps) {
  const router = useRouter();
  const [connected, setConnected] = useState(false);
  const [deviceWarning, setDeviceWarning] = useState<string | null>(null);
  const [roomRoot, setRoomRoot] = useState<HTMLDivElement | null>(null);
  const [sidePanel, setSidePanel] = useState<'NONE' | 'DOCUMENTS' | 'POLL' | 'QNA' | 'SPEAKING'>(
    'NONE',
  );
  const pointMeeting = creationMode === 'POINT' || join.participant.pointId != null;
  const speakingPanelId = `meeting-${meetingId}-speaking`;
  const [speakingChangeRevision, setSpeakingChangeRevision] = useState(0);
  const [speakingRefreshKey, setSpeakingRefreshKey] = useState(0);
  const speakingChanged = useCallback(
    () => setSpeakingChangeRevision((current) => current + 1),
    [],
  );
  const refreshSpeaking = useCallback(() => setSpeakingRefreshKey((current) => current + 1), []);
  const qnaPanelId = `meeting-${meetingId}-qna`;
  const [qnaChangeRevision, setQnaChangeRevision] = useState(0);
  const [qnaRefreshKey, setQnaRefreshKey] = useState(0);
  const qnaChanged = useCallback(() => setQnaChangeRevision((current) => current + 1), []);
  const refreshQna = useCallback(() => setQnaRefreshKey((current) => current + 1), []);
  const [documentsPanelKey, setDocumentsPanelKey] = useState(0);
  const [endingMeeting, setEndingMeeting] = useState(false);
  const [endControlMount, setEndControlMount] = useState<HTMLDivElement | null>(null);
  const fullscreenRootRef = useRef<HTMLDivElement>(null);
  const endedByEndActionRef = useRef(false);

  function handleRoomError(error: Error) {
    if (isRecoverableMediaDeviceError(error)) {
      setDeviceWarning(getMediaDeviceErrorMessage(error));
      return;
    }

    onError(error);
  }

  async function handleEndMeeting() {
    if (endingMeeting) return;
    const confirmed = window.confirm('Bạn có chắc chắn muốn kết thúc cuộc họp này không?');
    if (!confirmed) return;

    setEndingMeeting(true);
    try {
      const cleanupPending = await endManagementMeeting(meetingId);
      endedByEndActionRef.current = true;
      toast.success(
        cleanupPending
          ? 'Đã ghi nhận kết thúc cuộc họp; hệ thống đang đóng các kết nối còn lại.'
          : 'Đã kết thúc cuộc họp.',
      );
      router.push(`/management/meetings/${meetingId}`);
    } catch (endError) {
      toast.error(endError instanceof Error ? endError.message : 'Không thể kết thúc cuộc họp.');
      setEndingMeeting(false);
    }
  }

  useEffect(() => {
    const fullscreenRoot = fullscreenRootRef.current;
    if (!fullscreenRoot || !roomRoot) return;

    function isRoomFullscreenTrigger(target: EventTarget | null) {
      return target instanceof Element && Boolean(target.closest('.lk-focus-toggle-button'));
    }

    async function handleClick(event: MouseEvent) {
      if (!isRoomFullscreenTrigger(event.target)) return;
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      const targetRoot = fullscreenRoot as HTMLDivElement;

      try {
        if (document.fullscreenElement === targetRoot) {
          await document.exitFullscreen();
          return;
        }
        if (!document.fullscreenElement) {
          await targetRoot.requestFullscreen();
        }
      } catch (error) {
        console.error('Could not toggle meeting room fullscreen:', error);
      }
    }

    roomRoot.addEventListener('click', handleClick, true);
    return () => roomRoot.removeEventListener('click', handleClick, true);
  }, [roomRoot]);

  useEffect(() => {
    if (!join.permissions.canEndMeeting || !roomRoot) return;

    const controlBar = roomRoot.querySelector<HTMLElement>('.lk-control-bar');
    const leaveButton = controlBar?.querySelector<HTMLElement>('.lk-disconnect-button');
    if (!controlBar || !leaveButton) return;

    const target = document.createElement('div');
    target.className = styles.endMeetingControlMount;
    controlBar.insertBefore(target, leaveButton);
    setEndControlMount(target);

    return () => {
      setEndControlMount(null);
      target.remove();
    };
  }, [join.permissions.canEndMeeting, roomRoot]);

  return (
    <div ref={fullscreenRootRef} className={styles.roomFullscreenRoot}>
      <div className={styles.roomWithPanel}>
        <div ref={setRoomRoot} className={styles.roomRoot} data-lk-theme="default">
          <LiveKitRoom
            serverUrl={join.serverUrl}
            token={join.token}
            connect
            audio={false}
            video={false}
            options={{ adaptiveStream: true, dynacast: true }}
            onConnected={() => setConnected(true)}
            onDisconnected={(reason) => {
              setConnected(false);
              if (!endedByEndActionRef.current) {
                if (reason === DisconnectReason.CLIENT_INITIATED) router.push('/');
                else onDisconnected(reason);
              }
            }}
            onError={handleRoomError}
            onMediaDeviceFailure={(failure, kind) => {
              if (failure) {
                setDeviceWarning(getMediaDeviceErrorMessage(new Error(failure), kind));
              }
            }}
          >
            <ManagementMediaInitializer
              connected={connected}
              userChoices={userChoices}
              deviceWarning={deviceWarning}
            />
            <ManagementConferenceLayout
              meetingId={meetingId}
              chatWriteToken={join.participant.chatWriteToken}
              connectionId={join.participant.connectionId}
            />
            <ManagementToolbarVisibility roomRoot={roomRoot} />
            {pointMeeting ? (
              <ManagementSpeakingBridge
                roomRoot={roomRoot}
                active={sidePanel === 'SPEAKING'}
                panelId={speakingPanelId}
                meetingId={meetingId}
                changeRevision={speakingChangeRevision}
                onRefresh={refreshSpeaking}
                onToggle={() =>
                  setSidePanel((current) => (current === 'SPEAKING' ? 'NONE' : 'SPEAKING'))
                }
              />
            ) : null}
            <ManagementPollBridge
              roomRoot={roomRoot}
              active={sidePanel === 'POLL'}
              onToggle={() => setSidePanel((current) => (current === 'POLL' ? 'NONE' : 'POLL'))}
            />
            <ManagementQnaBridge
              roomRoot={roomRoot}
              active={sidePanel === 'QNA'}
              panelId={qnaPanelId}
              meetingId={meetingId}
              changeRevision={qnaChangeRevision}
              onRefresh={refreshQna}
              onToggle={() => setSidePanel((current) => (current === 'QNA' ? 'NONE' : 'QNA'))}
            />
            {canManageDocuments ? (
              <ManagementDocumentsBridge
                meetingId={meetingId}
                roomRoot={roomRoot}
                active={sidePanel === 'DOCUMENTS'}
                onToggleDocuments={() =>
                  setSidePanel((current) => (current === 'DOCUMENTS' ? 'NONE' : 'DOCUMENTS'))
                }
                onUploaded={() => {
                  setDocumentsPanelKey((current) => current + 1);
                  setSidePanel('DOCUMENTS');
                }}
              />
            ) : null}
            <ManagementCaptureControl
              meetingId={meetingId}
              canControl={join.permissions.canManageParticipants}
              roomRoot={roomRoot}
            />
            {join.permissions.canEndMeeting && endControlMount
              ? createPortal(
                  <button
                    type="button"
                    className={`lk-button ${styles.endMeetingButton}`}
                    onClick={() => void handleEndMeeting()}
                    disabled={endingMeeting}
                    aria-busy={endingMeeting}
                    aria-label={endingMeeting ? 'Đang kết thúc...' : 'Kết thúc cuộc họp'}
                    title={endingMeeting ? 'Đang kết thúc...' : 'Kết thúc cuộc họp'}
                  >
                    <ManagementRoomToolbarIcon name="endMeeting" />
                    <span className={styles.roomToolbarLabel}>
                      {endingMeeting ? 'Đang kết thúc...' : 'Kết thúc cuộc họp'}
                    </span>
                  </button>,
                  endControlMount,
                )
              : null}
          </LiveKitRoom>
        </div>
        {pointMeeting && sidePanel === 'SPEAKING' ? (
          <aside
            id={speakingPanelId}
            className={styles.documentsSidePanel}
            aria-label="Lịch sử phát biểu"
          >
            <MeetingSpeakingPanel
              meetingId={meetingId}
              attendeeId={join.participant.attendeeId}
              participantToken={join.participant.chatWriteToken}
              refreshKey={speakingRefreshKey}
              onChanged={speakingChanged}
              onClose={() => setSidePanel('NONE')}
            />
          </aside>
        ) : null}
        {sidePanel === 'POLL' ? (
          <aside className={styles.documentsSidePanel}>
            <MeetingPollPanel
              meetingId={meetingId}
              participantToken={join.participant.chatWriteToken}
              onClose={() => setSidePanel('NONE')}
            />
          </aside>
        ) : null}
        {sidePanel === 'QNA' ? (
          <aside id={qnaPanelId} className={styles.documentsSidePanel} aria-label="Hỏi đáp">
            <MeetingQnaPanel
              meetingId={meetingId}
              participantToken={join.participant.chatWriteToken}
              historyHref={
                join.participant.role !== 'GUEST'
                  ? `/management/meetings/${encodeURIComponent(meetingId)}/qa`
                  : undefined
              }
              onChanged={qnaChanged}
              refreshKey={qnaRefreshKey}
              onClose={() => setSidePanel('NONE')}
            />
          </aside>
        ) : null}
        {canManageDocuments && sidePanel === 'DOCUMENTS' ? (
          <aside className={styles.documentsSidePanel}>
            <MeetingDocumentsPanel
              key={documentsPanelKey}
              meetingId={meetingId}
              compact
              onClose={() => setSidePanel('NONE')}
            />
          </aside>
        ) : null}
      </div>
    </div>
  );
}
