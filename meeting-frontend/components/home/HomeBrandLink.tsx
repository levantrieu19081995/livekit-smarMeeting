import type { ReactNode } from 'react';
import Link from 'next/link';
import styles from '@/styles/HomeBrandLink.module.css';

export function HomeBrandLink({
  className,
  children = 'CUSC Smart Meeting',
}: {
  className?: string;
  children?: ReactNode;
}) {
  return (
    <Link
      href="/"
      className={`${styles.link}${className ? ` ${className}` : ''}`}
      aria-label="CUSC Smart Meeting"
    >
      {children}
    </Link>
  );
}
