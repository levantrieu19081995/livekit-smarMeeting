import styles from '@/styles/HomeImmersive.module.css';

export function StoryAtmosphere() {
  return (
    <div className={styles.spotlight} aria-hidden="true">
      <div className={`${styles.spotlightOrb} ${styles.spotlightOrbA}`} />
      <div className={`${styles.spotlightOrb} ${styles.spotlightOrbB}`} />
      <div className={styles.spotlightBeam} />
    </div>
  );
}
