'use client';

import { useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { ClassicHome } from './ClassicHome';
import { HomeHeader } from './HomeHeader';
import { ImmersiveHome, preloadImmersiveHome } from './ImmersiveHome';
import { useHomeMeetingActions } from '@/hooks/useHomeMeetingActions';
import homeStyles from '@/styles/Home.module.css';
import immersiveStyles from '@/styles/HomeImmersive.module.css';

export type HomeMode = 'classic' | 'immersive';
export type HomeTransitionState = 'idle' | 'transitioning';

const HOME_MODE_STORAGE_KEY = 'smartmeeting.home.mode';
const HOME_TRANSITION_CLASS = 'home-mode-transition';

function isHomeMode(value: string | null): value is HomeMode {
  return value === 'classic' || value === 'immersive';
}

function storeHomeMode(mode: HomeMode) {
  try {
    window.localStorage.setItem(HOME_MODE_STORAGE_KEY, mode);
  } catch {
    // The selected mode still applies for this session when storage is unavailable.
  }
}

export function HomePageClient() {
  const [mode, setMode] = useState<HomeMode | null>(null);
  const [transitionState, setTransitionState] = useState<HomeTransitionState>('idle');
  const homeScrollRef = useRef<HTMLElement>(null);
  const transitionLockedRef = useRef(false);
  const meetingActions = useHomeMeetingActions();

  useEffect(() => {
    let restoredMode: HomeMode = 'classic';

    try {
      const storedMode = window.localStorage.getItem(HOME_MODE_STORAGE_KEY);
      if (isHomeMode(storedMode)) {
        restoredMode = storedMode;
      }
    } catch {
      // Use the default when storage is unavailable.
    }

    setMode(restoredMode);
  }, []);

  useEffect(() => {
    if (mode !== 'classic' || transitionState !== 'idle') {
      return;
    }

    const idleWindow = window as unknown as {
      requestIdleCallback?: Window['requestIdleCallback'];
      cancelIdleCallback?: Window['cancelIdleCallback'];
    };

    if (idleWindow.requestIdleCallback && idleWindow.cancelIdleCallback) {
      const idleCallbackId = idleWindow.requestIdleCallback(preloadImmersiveHome, {
        timeout: 1500,
      });
      return () => idleWindow.cancelIdleCallback?.(idleCallbackId);
    }

    const preloadTimer = window.setTimeout(preloadImmersiveHome, 0);
    return () => window.clearTimeout(preloadTimer);
  }, [mode, transitionState]);

  useEffect(() => () => document.documentElement.classList.remove(HOME_TRANSITION_CLASS), []);

  function requestModeChange(nextMode: HomeMode) {
    if (!mode || nextMode === mode || transitionLockedRef.current) {
      return;
    }

    const finishTransition = () => {
      document.documentElement.classList.remove(HOME_TRANSITION_CLASS);
      transitionLockedRef.current = false;
      setTransitionState('idle');
    };

    transitionLockedRef.current = true;
    setTransitionState('transitioning');

    const commitModeChange = () => {
      homeScrollRef.current?.scrollTo({ top: 0, left: 0, behavior: 'auto' });
      flushSync(() => setMode(nextMode));
      storeHomeMode(nextMode);
    };

    if (typeof document.startViewTransition !== 'function') {
      commitModeChange();
      window.requestAnimationFrame(finishTransition);
      return;
    }

    document.documentElement.classList.add(HOME_TRANSITION_CLASS);

    try {
      const viewTransition = document.startViewTransition(commitModeChange);
      void viewTransition.finished.then(finishTransition, finishTransition);
    } catch {
      document.documentElement.classList.remove(HOME_TRANSITION_CLASS);
      commitModeChange();
      window.requestAnimationFrame(finishTransition);
    }
  }

  function renderExperience(experienceMode: HomeMode) {
    const sharedProps = {
      meetingId: meetingActions.meetingId,
      joinError: meetingActions.joinError,
      onMeetingIdChange: meetingActions.updateMeetingId,
      onJoinSubmit: meetingActions.submitJoin,
      onCreateMeeting: meetingActions.createMeeting,
      creatingMeeting: meetingActions.creatingMeeting,
      onShowJoin: meetingActions.showJoin,
      meetingListHref: meetingActions.meetingListHref,
    };

    if (experienceMode === 'immersive') {
      return <ImmersiveHome {...sharedProps} scrollContainerRef={homeScrollRef} />;
    }

    return <ClassicHome {...sharedProps} />;
  }

  const isTransitioning = transitionState !== 'idle';

  return (
    <main
      ref={homeScrollRef}
      className={`${homeStyles.main} ${
        mode !== 'classic' ? immersiveStyles.mainImmersive : ''
      } ${isTransitioning ? immersiveStyles.mainTransitioning : ''}`}
    >
      {mode ? (
        <>
          <HomeHeader
            mode={mode}
            transitionState={transitionState}
            onModeChange={requestModeChange}
          />

          <div
            className={`${immersiveStyles.experienceViewport} ${
              isTransitioning ? immersiveStyles.experienceViewportTransitioning : ''
            }`}
          >
            <div
              key={mode}
              className={`${immersiveStyles.experienceLayer} ${
                isTransitioning ? immersiveStyles.experienceLayerTransitioning : ''
              }`}
              inert={isTransitioning}
            >
              {renderExperience(mode)}
            </div>
          </div>
        </>
      ) : null}
    </main>
  );
}
