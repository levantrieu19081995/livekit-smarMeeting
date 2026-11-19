'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { createPortal } from 'react-dom';
import type { MouseEvent } from 'react';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import { useInstantMeetingCreation } from '@/hooks/useInstantMeetingCreation';
import { CreatedMeetingDialog } from '@/components/meetings/CreatedMeetingDialog';
import { HomeIcon } from './HomeIcon';
import styles from '@/styles/HomeMeetingCreateOptions.module.css';

export function HomeMeetingCreateOptions({ dark = false }: { dark?: boolean }) {
  const router = useRouter();
  const { authenticated, loading } = useManagementAuth();
  const { pending, created, createInstant, closeCreated } = useInstantMeetingCreation();
  const blocked = loading || pending;

  function guardNavigation(event: MouseEvent<HTMLAnchorElement>) {
    if (blocked) event.preventDefault();
  }

  function destination(mode: 'LATER' | 'PRIVATE' | 'POINT') {
    const target = `/management/meetings/create?mode=${mode}`;
    return authenticated ? target : `/auth/login?returnTo=${encodeURIComponent(target)}`;
  }

  function instant() {
    if (loading || pending) return;
    if (!authenticated) {
      router.push(`/auth/login?returnTo=${encodeURIComponent('/?tab=create')}`);
      return;
    }
    void createInstant();
  }

  return (
    <>
      <div
        className={`${styles.options} ${dark ? styles.dark : ''}`}
        aria-label="Chọn kiểu tạo cuộc họp"
      >
        <button type="button" onClick={instant} disabled={blocked} aria-busy={pending}>
          <HomeIcon name="video" />
          <span>{pending ? 'Đang tạo cuộc họp…' : 'Bắt đầu cuộc họp tức thì'}</span>
          <span className={styles.arrow} aria-hidden="true">
            →
          </span>
        </button>
        <Link href={destination('LATER')} aria-disabled={blocked} onClick={guardNavigation}>
          <HomeIcon name="calendar" />
          <span>Tạo cuộc họp để sử dụng sau</span>
          <span className={styles.arrow} aria-hidden="true">
            →
          </span>
        </Link>
        <Link href={destination('PRIVATE')} aria-disabled={blocked} onClick={guardNavigation}>
          <HomeIcon name="shield" />
          <span>Tạo cuộc họp riêng tư</span>
          <span className={styles.arrow} aria-hidden="true">
            →
          </span>
        </Link>
        <Link href={destination('POINT')} aria-disabled={blocked} onClick={guardNavigation}>
          <HomeIcon name="users" />
          <span>Tạo cuộc họp điểm cầu</span>
          <span className={styles.arrow} aria-hidden="true">
            →
          </span>
        </Link>
      </div>
      {created
        ? createPortal(
            <CreatedMeetingDialog meeting={created} onClose={closeCreated} />,
            document.body,
          )
        : null}
    </>
  );
}
