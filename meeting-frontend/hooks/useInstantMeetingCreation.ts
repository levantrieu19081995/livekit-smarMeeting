'use client';

import { useEffect, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import {
  createManagementMeeting,
  getMeetingCreationFingerprint,
} from '@/services/management/managementMeetingApi';
import {
  getMeetingCreationRequestId,
  confirmMeetingCreationRequest,
} from '@/services/management/pointCreationDraft';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import { ManagementApiError } from '@/services/management/httpClient';
import type { ManagementMeeting } from '@/types/management/meeting';

export function useInstantMeetingCreation(onCreated?: (meeting: ManagementMeeting) => void) {
  const { authenticated, user, loading } = useManagementAuth();
  const busy = useRef(false);
  const [pending, setPending] = useState(false);
  const [created, setCreated] = useState<ManagementMeeting | null>(null);
  const generation = useRef(0);
  const active = useRef(false);
  const account = !loading && authenticated ? user?.publicId : null;
  const scope = useRef(account);
  scope.current = account;
  useEffect(() => {
    active.current = true;
    generation.current++;
    busy.current = false;
    setPending(false);
    setCreated(null);
    return () => {
      active.current = false;
      generation.current++;
    };
  }, [account]);

  async function createInstant() {
    if (busy.current) return;
    if (!account) {
      toast.error('Vui lòng đăng nhập lại trước khi tạo cuộc họp.');
      return;
    }
    const requestAccount = account;
    const requestGeneration = generation.current;
    busy.current = true;
    setPending(true);
    let clientRequestId: string | undefined;
    try {
      const input = {
        title: '',
        description: '',
        accessType: 'PUBLIC' as const,
        creationMode: 'INSTANT' as const,
        participants: [],
        documents: [],
      };
      clientRequestId = getMeetingCreationRequestId(
        requestAccount,
        'INSTANT',
        getMeetingCreationFingerprint(input),
      );
      const meeting = await createManagementMeeting({ ...input, clientRequestId });
      confirmMeetingCreationRequest(requestAccount, 'INSTANT', clientRequestId);
      if (
        !active.current ||
        generation.current !== requestGeneration ||
        scope.current !== requestAccount
      )
        return;
      setCreated(meeting);
      onCreated?.(meeting);
    } catch (error) {
      if (
        clientRequestId &&
        error instanceof ManagementApiError &&
        error.status === 410 &&
        error.code === 'MEETING_CREATION_RECEIPT_DELETED'
      ) {
        confirmMeetingCreationRequest(requestAccount, 'INSTANT', clientRequestId);
      }
      if (
        !active.current ||
        generation.current !== requestGeneration ||
        scope.current !== requestAccount
      )
        return;
      toast.error(error instanceof Error ? error.message : 'Không thể tạo cuộc họp.');
    } finally {
      if (
        active.current &&
        generation.current === requestGeneration &&
        scope.current === requestAccount
      ) {
        busy.current = false;
        setPending(false);
      }
    }
  }

  return { pending, created, createInstant, closeCreated: () => setCreated(null) };
}
