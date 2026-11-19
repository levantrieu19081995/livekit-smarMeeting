'use client';

import Image from 'next/image';
import { useEffect, useState } from 'react';
import { ManagementLoginForm } from '@/components/auth/ManagementLoginForm';
import { ManagementRegisterForm } from '@/components/auth/ManagementRegisterForm';
import styles from '@/styles/management/AuthPages.module.css';

type AuthMode = 'login' | 'register';

function removeLegacyModeQuery() {
  const url = new URL(window.location.href);

  if (!url.searchParams.has('mode')) {
    return;
  }

  url.searchParams.delete('mode');
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
}

export function ManagementAuthExperience() {
  const [mode, setMode] = useState<AuthMode>('login');

  const registerActive = mode === 'register';
  const loginActive = mode === 'login';

  useEffect(() => {
    removeLegacyModeQuery();
  }, []);

  function changeMode(nextMode: AuthMode) {
    setMode(nextMode);
  }

  function handleShowRegister() {
    changeMode('register');
  }

  function handleShowLogin() {
    changeMode('login');
  }

  return (
    <div className={styles.authPage}>
      <main className={styles.authContainer}>
        <section
          className={`${styles.formPane} ${styles.registerPane} ${
            registerActive ? styles.paneActive : styles.paneInactive
          }`}
          aria-hidden={!registerActive}
        >
          <ManagementRegisterForm active={registerActive} onRequestLogin={handleShowLogin} />
        </section>

        <section
          className={`${styles.formPane} ${styles.loginPane} ${
            loginActive ? styles.paneActive : styles.paneInactive
          }`}
          aria-hidden={!loginActive}
        >
          <ManagementLoginForm active={loginActive} onRequestRegister={handleShowRegister} />
        </section>

        <aside
          className={`${styles.visualPanel} ${
            loginActive ? styles.visualPanelLeft : styles.visualPanelRight
          }`}
        >
          <Image
            src="/background-images/samantha-gades-BlIhVfXbi9s-unsplash.jpg"
            alt=""
            fill
            priority
            sizes="(max-width: 768px) 100vw, 50vw"
            className={styles.visualImage}
          />
          <div className={styles.visualOverlay} />

          <div className={styles.visualContent}>
            <div
              className={`${styles.panelContent} ${loginActive ? styles.panelContentVisible : ''}`}
              aria-hidden={!loginActive}
            >
              <p className={styles.panelKicker}>SMART MEETING</p>
              <h2>
                {'Ch\u00e0o m\u1eebng b\u1ea1n tr\u1edf l\u1ea1i.'}
              </h2>
              <p>
                {
                  '\u0110\u0103ng nh\u1eadp \u0111\u1ec3 ti\u1ebfp t\u1ee5c qu\u1ea3n l\u00fd v\u00e0 tham gia c\u00e1c cu\u1ed9c h\u1ecdp c\u1ee7a b\u1ea1n.'
                }
              </p>
              <div className={styles.panelSwitchBlock}>
                <span>{'Ch\u01b0a c\u00f3 t\u00e0i kho\u1ea3n?'}</span>
                <button type="button" onClick={handleShowRegister} disabled={!loginActive}>
                  {'\u0110\u0103ng k\u00fd'}
                </button>
              </div>
            </div>

            <div
              className={`${styles.panelContent} ${registerActive ? styles.panelContentVisible : ''}`}
              aria-hidden={!registerActive}
            >
              <p className={styles.panelKicker}>SMART MEETING</p>
              <h2>{'Cu\u1ed9c h\u1ecdp li\u1ec1n m\u1ea1ch, c\u1ed9ng t\u00e1c hi\u1ec7u qu\u1ea3.'}</h2>
              <p>
                {
                  'K\u1ebft n\u1ed1i, qu\u1ea3n l\u00fd v\u00e0 l\u01b0u l\u1ea1i n\u1ed9i dung cu\u1ed9c h\u1ecdp trong m\u1ed9t kh\u00f4ng gian th\u1ed1ng nh\u1ea5t.'
                }
              </p>
              <div className={styles.panelSwitchBlock}>
                <span>{'\u0110\u00e3 c\u00f3 t\u00e0i kho\u1ea3n?'}</span>
                <button type="button" onClick={handleShowLogin} disabled={!registerActive}>
                  {'\u0110\u0103ng nh\u1eadp'}
                </button>
              </div>
            </div>
          </div>
        </aside>
      </main>
    </div>
  );
}
