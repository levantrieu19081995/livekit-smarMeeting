'use client';

import type { FormEvent, RefObject } from 'react';
import dynamic from 'next/dynamic';
import styles from '@/styles/HomeImmersive.module.css';

const loadScrollStory = () =>
  import('./immersive/ScrollStory').then((module) => module.ScrollStory);

const ScrollStory = dynamic(loadScrollStory, {
  ssr: false,
  loading: () => (
    <div className={styles.storyLoading} role="status" aria-live="polite">
      <span className={styles.splineLoader} aria-hidden="true" />
      <span>Đang chuẩn bị không gian 3D…</span>
    </div>
  ),
});

export function preloadImmersiveHome() {
  void loadScrollStory();
}

export type ImmersiveHomeProps = {
  meetingId: string;
  joinError: string;
  onMeetingIdChange: (value: string) => void;
  onJoinSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onCreateMeeting: () => void;
  creatingMeeting: boolean;
  onShowJoin: () => void;
  meetingListHref: string;
  scrollContainerRef: RefObject<HTMLElement>;
};

export function ImmersiveHome(props: ImmersiveHomeProps) {
  return <ScrollStory {...props} />;
}
