import type { ReactNode } from 'react';
import Link from 'next/link';
import styles from '@/styles/management/ManagementShell.module.css';

interface ManagementPageHeroAction {
  label: string;
  href?: string;
  icon?: ReactNode;
  variant?: 'primary' | 'secondary';
  disabled?: boolean;
  title?: string;
}

interface ManagementPageHeroTagline {
  line1: string;
  line2: string;
}

interface ManagementPageHeroProps {
  eyebrow: ReactNode;
  title: string;
  subtitle: ReactNode;
  actions?: ManagementPageHeroAction[];
  actionsContent?: ReactNode;
  tagline?: ManagementPageHeroTagline;
}

export function ManagementPageHero({
  eyebrow,
  title,
  subtitle,
  actions = [],
  actionsContent,
  tagline,
}: ManagementPageHeroProps) {
  return (
    <section className={styles.pageHero}>
      <div className={styles.pageHeroOverlay} />
      <div className={styles.pageHeroContent}>
        <p className={styles.pageHeroEyebrow}>{eyebrow}</p>
        <h1>{title}</h1>
        <p className={styles.pageHeroSubtitle}>{subtitle}</p>
        {actions.length > 0 || actionsContent ? (
          <div className={styles.pageHeroActions}>
            {actionsContent}
            {actions.map((action) => {
              const actionClassName =
                action.variant === 'secondary'
                  ? styles.pageHeroSecondaryAction
                  : styles.pageHeroPrimaryAction;
              const actionContent = (
                <>
                  {action.icon ? (
                    <span className={styles.pageHeroActionIcon}>{action.icon}</span>
                  ) : null}
                  <span>{action.label}</span>
                </>
              );

              if (action.disabled || !action.href) {
                return (
                  <button
                    key={`${action.label}-disabled`}
                    type="button"
                    className={actionClassName}
                    disabled
                    title={action.title}
                  >
                    {actionContent}
                  </button>
                );
              }

              return (
                <Link
                  key={`${action.href}-${action.label}`}
                  href={action.href}
                  className={actionClassName}
                  title={action.title}
                >
                  {actionContent}
                </Link>
              );
            })}
          </div>
        ) : null}
      </div>
      {tagline ? (
        <div className={styles.pageHeroTagline}>
          <span>{tagline.line1}</span>
          <span>{tagline.line2}</span>
          <i aria-hidden="true" />
        </div>
      ) : null}
    </section>
  );
}
