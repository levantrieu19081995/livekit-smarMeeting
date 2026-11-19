import type { FormEvent } from 'react';
import Link from 'next/link';
import { HomeIcon } from './HomeIcon';
import { HomeMeetingCreateOptions } from './HomeMeetingCreateOptions';
import styles from '@/styles/Home.module.css';

type ClassicHomeProps = {
  meetingId: string;
  joinError: string;
  onMeetingIdChange: (value: string) => void;
  onJoinSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onCreateMeeting: () => void;
  creatingMeeting: boolean;
  onShowJoin: () => void;
  meetingListHref: string;
};

export function ClassicHome({
  meetingId,
  joinError,
  onMeetingIdChange,
  onJoinSubmit,
  onCreateMeeting,
  creatingMeeting,
  onShowJoin,
  meetingListHref,
}: ClassicHomeProps) {
  return (
    <>
      <section className={styles.hero} aria-labelledby="home-title">
        <div className={styles.heroContent}>
          <h1 id="home-title">
            Họp trực tuyến dễ dàng
            <br className={styles.desktopBreak} />
            kết nối mọi người
          </h1>
          <p>
            CUSC Smart Meeting giúp bạn tổ chức, tham gia và quản lý
            <br className={styles.desktopBreak} />
            cuộc họp trực tuyến một cách đơn giản, an toàn và hiệu quả.
          </p>
        </div>

        <section className={styles.joinCard} aria-label="Tham gia hoặc tạo cuộc họp">
          <div className={styles.cardTabs} role="tablist" aria-label="Tác vụ cuộc họp">
            <button
              className={creatingMeeting ? styles.inactiveTab : styles.activeTab}
              type="button"
              role="tab"
              id="home-join-tab"
              aria-controls="home-join-panel"
              aria-selected={!creatingMeeting}
              onClick={onShowJoin}
            >
              <HomeIcon name="users" />
              Tham gia cuộc họp
            </button>
            <button
              className={creatingMeeting ? styles.activeTab : styles.inactiveTab}
              type="button"
              role="tab"
              id="home-create-tab"
              aria-controls="home-create-panel"
              aria-selected={creatingMeeting}
              onClick={onCreateMeeting}
            >
              <HomeIcon name="calendar" />
              Tạo cuộc họp mới
            </button>
          </div>

          <div
            role="tabpanel"
            id="home-join-panel"
            aria-labelledby="home-join-tab"
            hidden={creatingMeeting}
          >
            <form className={styles.joinForm} onSubmit={onJoinSubmit} noValidate>
              <label className={styles.inputLabel} htmlFor="home-meeting-id">
                Mã cuộc họp
              </label>
              <div className={styles.inputWrap}>
                <span aria-hidden="true">#</span>
                <input
                  id="home-meeting-id"
                  type="text"
                  inputMode="numeric"
                  pattern="[0-9]*"
                  value={meetingId}
                  onChange={(event) => onMeetingIdChange(event.target.value)}
                  placeholder="Nhập mã cuộc họp (ví dụ: 22)"
                  aria-describedby={joinError ? 'home-meeting-id-error' : undefined}
                  aria-invalid={joinError ? 'true' : 'false'}
                />
              </div>

              {joinError ? (
                <p className={styles.validationMessage} id="home-meeting-id-error">
                  {joinError}
                </p>
              ) : null}

              <button className={styles.joinButton} type="submit">
                <HomeIcon name="video" />
                Tham gia
              </button>
            </form>

            <div className={styles.divider} aria-hidden="true">
              <span>hoặc</span>
            </div>

            <Link className={styles.meetingsLink} href={meetingListHref}>
              <HomeIcon name="list" />
              Xem danh sách cuộc họp của bạn
            </Link>
          </div>
          <div
            role="tabpanel"
            id="home-create-panel"
            aria-labelledby="home-create-tab"
            hidden={!creatingMeeting}
          >
            <HomeMeetingCreateOptions />
          </div>
        </section>
      </section>

      <section className={styles.featureStrip} aria-label="Điểm nổi bật">
        <article className={styles.featureItem}>
          <span className={styles.featureIcon} aria-hidden="true">
            <HomeIcon name="users" />
          </span>
          <h2>Họp mọi lúc, mọi nơi</h2>
          <p>
            Kết nối nhanh chóng
            <br className={styles.desktopBreak} />
            chỉ với một đường link.
          </p>
        </article>

        <article className={styles.featureItem}>
          <span className={styles.featureIcon} aria-hidden="true">
            <HomeIcon name="shield" />
          </span>
          <h2>An toàn, bảo mật</h2>
          <p>
            Bảo vệ thông tin
            <br className={styles.desktopBreak} />
            và dữ liệu cuộc họp.
          </p>
        </article>

        <article className={styles.featureItem}>
          <span className={styles.featureIcon} aria-hidden="true">
            <HomeIcon name="document" />
          </span>
          <h2>Biên bản thông minh</h2>
          <p>
            Tự động ghi âm, chuyển giọng nói
            <br className={styles.desktopBreak} />
            thành văn bản và tạo biên bản.
          </p>
        </article>
      </section>
    </>
  );
}
