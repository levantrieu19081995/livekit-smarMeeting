import styles from '@/styles/management/PreJoinBackdrop.module.css';

export function ManagementPreJoinBackdrop() {
  return (
    <div className={styles.backdrop} aria-hidden="true">
      <span className={styles.stars} />
      <span className={`${styles.planet} ${styles.planetLeft}`} />
      <span className={`${styles.planet} ${styles.planetRight}`} />
      <span className={`${styles.orbit} ${styles.orbitLeft}`} />
      <span className={`${styles.orbit} ${styles.orbitRight}`} />
      <span className={`${styles.ring} ${styles.ringOuter}`} />
      <span className={`${styles.ring} ${styles.ringMiddle}`} />
      <span className={`${styles.ring} ${styles.ringInner}`} />
    </div>
  );
}
