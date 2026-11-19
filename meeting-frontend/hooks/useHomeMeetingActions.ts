'use client';

import type { FormEvent } from 'react';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';

const MEETING_LIST_HREF = '/management/meetings';

export function useHomeMeetingActions() {
  const router = useRouter();
  const [meetingId, setMeetingId] = useState('');
  const [joinError, setJoinError] = useState('');
  const [creatingMeeting, setCreatingMeeting] = useState(false);

  useEffect(() => {
    if (new URLSearchParams(window.location.search).get('tab') === 'create') {
      setCreatingMeeting(true);
    }
  }, []);

  function updateMeetingId(value: string) {
    setMeetingId(value);

    if (joinError) {
      setJoinError('');
    }
  }

  function submitJoin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const trimmedMeetingId = meetingId.trim();
    const parsedMeetingId = Number(trimmedMeetingId);

    if (
      !/^\d+$/.test(trimmedMeetingId) ||
      !Number.isInteger(parsedMeetingId) ||
      parsedMeetingId <= 0
    ) {
      setJoinError('Vui lòng nhập mã cuộc họp hợp lệ.');
      return;
    }

    setJoinError('');
    router.push(`/join/${trimmedMeetingId}`);
  }

  function createMeeting() {
    setCreatingMeeting(true);
  }

  return {
    meetingId,
    joinError,
    updateMeetingId,
    submitJoin,
    createMeeting,
    creatingMeeting,
    showJoin: () => setCreatingMeeting(false),
    meetingListHref: MEETING_LIST_HREF,
  };
}
