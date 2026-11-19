'use client';

import { Fragment, useEffect, useRef, useState, type FormEvent, type RefObject } from 'react';
import Link from 'next/link';
import {
  motion,
  useMotionValueEvent,
  useReducedMotion,
  useScroll,
  useSpring,
  useTransform,
  type MotionValue,
} from 'framer-motion';
import styles from '@/styles/HomeImmersive.module.css';
import { ImmersiveFinal } from './ImmersiveFinal';
import { SplineScene } from './SplineScene';
import { StoryAtmosphere } from './StoryAtmosphere';
import { HomeMeetingCreateOptions } from '../HomeMeetingCreateOptions';

const stages = [
  { type: 'join', key: 'join-create-meeting' },
  {
    type: 'text',
    key: 'smart-meeting',
    kicker: '01 · SMART MEETING',
    titleLines: ['Họp trực tuyến dễ dàng', 'kết nối mọi người'],
    textLines: [
      'CUSC Smart Meeting giúp bạn tổ chức,',
      'tham gia và quản lý cuộc họp trực tuyến',
      'một cách đơn giản, an toàn và hiệu quả.',
    ],
  },
  {
    type: 'text',
    key: 'connection',
    kicker: '02 · KẾT NỐI',
    titleLines: ['Họp mọi lúc, mọi nơi'],
    textLines: ['Kết nối nhanh chóng', 'chỉ với một đường link.'],
  },
  {
    type: 'text',
    key: 'security',
    kicker: '03 · BẢO MẬT',
    titleLines: ['An toàn, bảo mật'],
    textLines: ['Bảo vệ thông tin', 'và dữ liệu cuộc họp.'],
  },
  {
    type: 'text',
    key: 'smart-minutes',
    kicker: '04 · BIÊN BẢN THÔNG MINH',
    titleLines: ['Biên bản thông minh'],
    textLines: ['Tự động ghi âm, chuyển giọng nói', 'thành văn bản và tạo biên bản.'],
  },
] as const;

const stageWindows = [
  { enterStart: 0, enterEnd: 0, exitStart: 0.115, exitEnd: 0.14 },
  { enterStart: 0.16, enterEnd: 0.19, exitStart: 0.275, exitEnd: 0.305 },
  { enterStart: 0.32, enterEnd: 0.35, exitStart: 0.435, exitEnd: 0.465 },
  { enterStart: 0.48, enterEnd: 0.51, exitStart: 0.595, exitEnd: 0.625 },
  { enterStart: 0.64, enterEnd: 0.67, exitStart: 0.735, exitEnd: 0.775 },
] as const;

type ScrollStoryProps = {
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

type TextStage = Extract<(typeof stages)[number], { type: 'text' }>;

function useMobileStoryLayout() {
  const [isMobile, setIsMobile] = useState(false);

  useEffect(() => {
    const media = window.matchMedia('(max-width: 760px)');
    const update = () => setIsMobile(media.matches);
    update();
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);

  return isMobile;
}

function TextStoryStage({
  index,
  stage,
  progress,
  timelineRef,
  enterDistance,
  exitDistance,
}: {
  index: number;
  stage: TextStage;
  progress: MotionValue<number>;
  timelineRef: (node: HTMLElement | null) => void;
  enterDistance: string;
  exitDistance: string;
}) {
  const { enterStart, enterEnd, exitStart, exitEnd } = stageWindows[index];
  const input = [0, enterStart, enterEnd, exitStart, exitEnd, 1];
  const opacity = useTransform(progress, input, [0, 0, 1, 1, 0, 0]);
  const y = useTransform(progress, input, [
    enterDistance,
    enterDistance,
    '0vh',
    '0vh',
    exitDistance,
    exitDistance,
  ]);
  const filter = useTransform(progress, input, [
    'blur(4px)',
    'blur(4px)',
    'blur(0px)',
    'blur(0px)',
    'blur(3px)',
    'blur(3px)',
  ]);

  return (
    <motion.article className={styles.storyStage} style={{ opacity, y, filter }}>
      <p className={styles.eyebrow}>{stage.kicker}</p>
      <h1>
        {stage.titleLines.map((line, lineIndex) => (
          <Fragment key={line}>
            {line}
            {lineIndex < stage.titleLines.length - 1 ? <br /> : null}
          </Fragment>
        ))}
      </h1>
      <p>
        {stage.textLines.map((line, lineIndex) => (
          <Fragment key={line}>
            {line}
            {lineIndex < stage.textLines.length - 1 ? <br /> : null}
          </Fragment>
        ))}
      </p>
      <small ref={timelineRef}>0% timeline</small>
    </motion.article>
  );
}

function JoinMeetingStage({
  progress,
  timelineRef,
  exitDistance,
  meetingId,
  joinError,
  onMeetingIdChange,
  onJoinSubmit,
  onCreateMeeting,
  creatingMeeting,
  onShowJoin,
  meetingListHref,
}: Omit<ScrollStoryProps, 'scrollContainerRef'> & {
  progress: MotionValue<number>;
  timelineRef: (node: HTMLElement | null) => void;
  exitDistance: string;
}) {
  const articleRef = useRef<HTMLElement>(null);
  const { exitStart, exitEnd } = stageWindows[0];
  const input = [0, exitStart, exitEnd, 1];
  const opacity = useTransform(progress, input, [1, 1, 0, 0]);
  const y = useTransform(progress, input, ['0vh', '0vh', exitDistance, exitDistance]);
  const filter = useTransform(progress, input, [
    'blur(0px)',
    'blur(0px)',
    'blur(2px)',
    'blur(2px)',
  ]);

  useEffect(() => {
    const article = articleRef.current;
    if (!article) return;

    const updateInteractivity = (value: number) => {
      const isInteractive = value > 0.01;
      article.inert = !isInteractive;
      article.style.pointerEvents = isInteractive ? 'auto' : 'none';
      if (isInteractive) article.removeAttribute('aria-hidden');
      else article.setAttribute('aria-hidden', 'true');
    };

    updateInteractivity(opacity.get());
    return opacity.on('change', updateInteractivity);
  }, [opacity]);

  return (
    <motion.article
      ref={articleRef}
      className={`${styles.storyStage} ${styles.storyJoinStage}`}
      style={{ opacity, y, filter }}
    >
      <form className={styles.storyJoinPanel} onSubmit={onJoinSubmit} noValidate>
        <div className={styles.storyJoinTabs} role="tablist" aria-label="Tùy chọn cuộc họp">
          <button
            type="button"
            role="tab"
            id="immersive-join-tab"
            aria-controls="immersive-join-panel"
            aria-selected={!creatingMeeting}
            className={!creatingMeeting ? styles.storyJoinTabActive : undefined}
            onClick={onShowJoin}
          >
            Tham gia cuộc họp
          </button>
          <button
            type="button"
            role="tab"
            id="immersive-create-tab"
            aria-controls="immersive-create-panel"
            aria-selected={creatingMeeting}
            className={creatingMeeting ? styles.storyJoinTabActive : undefined}
            onClick={onCreateMeeting}
          >
            Tạo cuộc họp mới
          </button>
        </div>

        <div
          hidden={creatingMeeting}
          role="tabpanel"
          id="immersive-join-panel"
          aria-labelledby="immersive-join-tab"
        >
          <div className={styles.storyJoinContent}>
            <label htmlFor="immersive-meeting-code">Mã cuộc họp</label>
            <input
              id="immersive-meeting-code"
              name="meetingCode"
              type="text"
              inputMode="numeric"
              pattern="[0-9]*"
              autoComplete="off"
              value={meetingId}
              onChange={(event) => onMeetingIdChange(event.target.value)}
              placeholder="Nhập mã cuộc họp (ví dụ: 22)"
              aria-describedby={joinError ? 'immersive-meeting-code-error' : undefined}
              aria-invalid={joinError ? 'true' : 'false'}
            />
            {joinError ? (
              <p className={styles.storyValidation} id="immersive-meeting-code-error">
                {joinError}
              </p>
            ) : null}
            <button className={styles.storyJoinPrimary} type="submit">
              Tham gia
            </button>
            <div className={styles.storyJoinDivider}>
              <span>hoặc</span>
            </div>
            <Link className={styles.storyJoinSecondary} href={meetingListHref}>
              Xem danh sách cuộc họp của bạn
            </Link>
          </div>
        </div>
        <div
          hidden={!creatingMeeting}
          role="tabpanel"
          id="immersive-create-panel"
          aria-labelledby="immersive-create-tab"
        >
          <HomeMeetingCreateOptions dark />
        </div>
      </form>
      <small ref={timelineRef}>0% timeline</small>
    </motion.article>
  );
}

export function ScrollStory({
  meetingId,
  joinError,
  onMeetingIdChange,
  onJoinSubmit,
  onCreateMeeting,
  creatingMeeting,
  onShowJoin,
  meetingListHref,
  scrollContainerRef,
}: ScrollStoryProps) {
  const storyRef = useRef<HTMLDivElement>(null);
  const progressRef = useRef<HTMLDivElement>(null);
  const timelineRefs = useRef<Array<HTMLElement | null>>([]);
  const reduceMotion = useReducedMotion() ?? false;
  const isMobile = useMobileStoryLayout();
  const { scrollYProgress } = useScroll({
    container: scrollContainerRef,
    target: storyRef,
    offset: ['start start', 'end end'],
  });
  const springProgress = useSpring(scrollYProgress, {
    stiffness: 180,
    damping: 30,
    mass: 0.35,
    restDelta: 0.0005,
  });
  const sceneProgress = reduceMotion ? scrollYProgress : springProgress;
  const textEnterDistance = isMobile ? '20vh' : '30vh';
  const textExitDistance = isMobile ? '-20vh' : '-28vh';
  const formExitDistance = isMobile ? '-16vh' : '-20vh';
  const backgroundColor = useTransform(
    scrollYProgress,
    [0, 0.3, 0.6, 0.8, 1],
    ['rgb(5 8 13)', 'rgb(4 7 12)', 'rgb(2 5 10)', 'rgb(1 2 5)', 'rgb(0 0 0)'],
  );
  const storyOpacity = useTransform(scrollYProgress, [0, 0.8, 0.98, 1], [1, 1, 0, 0]);
  const atmosphereOpacity = useTransform(scrollYProgress, [0, 0.3, 0.8, 1], [1, 1, 0, 0]);
  const presentationOpacity = useTransform(scrollYProgress, [0, 0.78, 1], [0, 0, 1]);
  const titleOpacity = useTransform(scrollYProgress, [0, 0.82, 0.98, 1], [0, 0, 1, 1]);
  const descriptionOpacity = useTransform(scrollYProgress, [0, 0.86, 1], [0, 0, 1]);
  const sceneOpacity = useTransform(scrollYProgress, [0, 0.8, 0.9, 0.98, 1], [1, 1, 0.58, 0.9, 1]);
  const progressScale = useTransform(scrollYProgress, (value) => Math.max(0.02, value));
  const sceneScale = useTransform(sceneProgress, (value) => {
    const smoothProgress = value * value * (3 - 2 * value);
    return 1.05 + (0.88 - 1.05) * smoothProgress;
  });
  const sceneWidth = useTransform(
    sceneProgress,
    [0, 0.8, 1],
    isMobile ? ['238.257%', '238.257%', '100%'] : ['127.093%', '127.093%', '100%'],
  );
  const sceneHeight = useTransform(
    sceneProgress,
    [0, 0.8, 1],
    isMobile ? ['72.191%', '72.191%', '100%'] : ['110.978%', '110.978%', '100%'],
  );
  const sceneLeft = useTransform(
    sceneProgress,
    [0, 0.8, 1],
    isMobile ? ['-138.257%', '-138.257%', '0%'] : ['-20.74%', '-20.74%', '0%'],
  );
  const sceneTop = useTransform(
    sceneProgress,
    [0, 0.8, 1],
    isMobile ? ['36.078%', '36.078%', '0%'] : ['-7.274%', '-7.274%', '0%'],
  );
  const sceneOriginY = useTransform(sceneProgress, [0, 0.8, 1], ['52%', '52%', '50%']);
  const sceneX = useTransform(
    sceneProgress,
    [0, 0.3, 0.55, 0.6, 0.8, 1],
    ['0vw', '-2.4vw', '-4.4vw', '-3.1vw', '0.5vw', '0vw'],
  );

  useMotionValueEvent(scrollYProgress, 'change', (value) => {
    const percent = Math.round(value * 100);
    progressRef.current?.setAttribute('aria-valuenow', String(percent));
    timelineRefs.current.forEach((node) => {
      if (node) node.textContent = `${percent}% timeline`;
    });
  });

  const persistentScene = (
    <motion.div
      className={styles.storyScene}
      style={{
        opacity: sceneOpacity,
        x: sceneX,
        left: sceneLeft,
        top: sceneTop,
        width: sceneWidth,
        height: sceneHeight,
        scale: sceneScale,
        originY: sceneOriginY,
      }}
    >
      <SplineScene />
    </motion.div>
  );

  return (
    <div ref={storyRef} className={styles.storyRoot}>
      <motion.div className={styles.storySticky} style={{ backgroundColor }}>
        <motion.div className={styles.storyAtmosphere} style={{ opacity: atmosphereOpacity }}>
          <StoryAtmosphere />
          <div className={styles.storyGrid} />
        </motion.div>

        <motion.div
          ref={progressRef}
          className={styles.storyProgress}
          role="progressbar"
          aria-label="Tiến độ scroll story"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={0}
          style={{ opacity: storyOpacity }}
        >
          <motion.span style={{ scaleY: progressScale }} />
        </motion.div>

        <motion.div className={styles.storyCopy} style={{ opacity: storyOpacity }}>
          {stages.map((stage, index) =>
            stage.type === 'join' ? (
              <JoinMeetingStage
                key={stage.key}
                progress={scrollYProgress}
                exitDistance={formExitDistance}
                meetingId={meetingId}
                joinError={joinError}
                onMeetingIdChange={onMeetingIdChange}
                onJoinSubmit={onJoinSubmit}
                onCreateMeeting={onCreateMeeting}
                creatingMeeting={creatingMeeting}
                onShowJoin={onShowJoin}
                meetingListHref={meetingListHref}
                timelineRef={(node) => {
                  timelineRefs.current[index] = node;
                }}
              />
            ) : (
              <TextStoryStage
                key={stage.key}
                index={index}
                stage={stage}
                progress={scrollYProgress}
                enterDistance={textEnterDistance}
                exitDistance={textExitDistance}
                timelineRef={(node) => {
                  timelineRefs.current[index] = node;
                }}
              />
            ),
          )}
        </motion.div>

        <div className={styles.storyFinal}>
          <ImmersiveFinal
            presentationOpacity={presentationOpacity}
            titleOpacity={titleOpacity}
            descriptionOpacity={descriptionOpacity}
            sceneSlot={persistentScene}
          />
        </div>

        <motion.div className={styles.storyHint} style={{ opacity: storyOpacity }}>
          Cuộn xuống ↓
        </motion.div>
      </motion.div>
      <div className={styles.storySpacer} aria-hidden="true" />
    </div>
  );
}
