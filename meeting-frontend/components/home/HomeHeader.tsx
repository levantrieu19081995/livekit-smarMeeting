import Link from 'next/link';
import type { HomeMode, HomeTransitionState } from './HomePageClient';
import { HomeAccountPanel } from './HomeAccountPanel';
import { HomeHelpMenu } from './HomeHelpMenu';
import { HomeIcon } from './HomeIcon';
import { HomeModeSwitch } from './HomeModeSwitch';
import styles from '@/styles/Home.module.css';

type HomeHeaderProps = {
  mode: HomeMode;
  transitionState: HomeTransitionState;
  onModeChange: (mode: HomeMode) => void;
};

export function HomeHeader({ mode, transitionState, onModeChange }: HomeHeaderProps) {
  return (
    <header
      className={`${styles.homeHeader} ${mode === 'immersive' ? styles.homeHeaderImmersive : ''}`}
    >
      <Link className={styles.brand} href="/" aria-label="CUSC Smart Meeting">
        <span>CUSC Smart Meeting</span>
      </Link>

      <nav className={styles.headerNav} aria-label="Điều hướng trang chủ">
        <HomeHelpMenu icon={<HomeIcon name="help" />} />
        <HomeModeSwitch
          mode={mode}
          disabled={transitionState !== 'idle'}
          onModeChange={onModeChange}
        />
        <HomeAccountPanel />
      </nav>
    </header>
  );
}
