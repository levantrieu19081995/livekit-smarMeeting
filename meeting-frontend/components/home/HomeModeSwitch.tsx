import type { HomeMode } from './HomePageClient';
import styles from '@/styles/HomeImmersive.module.css';

type HomeModeSwitchProps = {
  mode: HomeMode;
  disabled: boolean;
  onModeChange: (mode: HomeMode) => void;
};

export function HomeModeSwitch({ mode, disabled, onModeChange }: HomeModeSwitchProps) {
  return (
    <div
      className={`${styles.modeSwitch} ${mode === 'immersive' ? styles.modeSwitchImmersive : ''}`}
      role="group"
      aria-label="Chế độ hiển thị trang chủ"
    >
      <button
        className={mode === 'classic' ? styles.modeSwitchActive : ''}
        type="button"
        aria-pressed={mode === 'classic'}
        disabled={disabled}
        onClick={() => onModeChange('classic')}
      >
        Cổ điển
      </button>
      <button
        className={mode === 'immersive' ? styles.modeSwitchActive : ''}
        type="button"
        aria-pressed={mode === 'immersive'}
        disabled={disabled}
        onClick={() => onModeChange('immersive')}
      >
        3D
      </button>
    </div>
  );
}
