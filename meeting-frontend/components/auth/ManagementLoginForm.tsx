'use client';

import { FormEvent, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { loginManagementAuth } from '@/services/management/managementAuthService';
import styles from '@/styles/management/AuthPages.module.css';

interface ManagementLoginFormProps {
  active: boolean;
  onRequestRegister: () => void;
}

function EyeIcon({ hidden }: { hidden: boolean }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path
        d={
          hidden
            ? 'M3.28 2 22 20.72 20.72 22l-3.1-3.1A11.45 11.45 0 0 1 12 20C7 20 2.73 16.89 1 12.5a11.8 11.8 0 0 1 4.1-5.22L2 4.18 3.28 2Zm7.14 7.14a3 3 0 0 0 4.44 4.44l-4.44-4.44ZM12 5c5 0 9.27 3.11 11 7.5a11.78 11.78 0 0 1-3.03 4.25l-2.35-2.35A6 6 0 0 0 9.6 6.38L7.8 4.58A11.2 11.2 0 0 1 12 5Z'
            : 'M12 5c5 0 9.27 3.11 11 7.5C21.27 16.89 17 20 12 20S2.73 16.89 1 12.5C2.73 8.11 7 5 12 5Zm0 3a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9Zm0 2a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5Z'
        }
      />
    </svg>
  );
}

export function ManagementLoginForm({ active, onRequestRegister }: ManagementLoginFormProps) {
  const router = useRouter();
  const firstInputRef = useRef<HTMLInputElement>(null);
  const [usernameOrEmail, setUsernameOrEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (active) {
      firstInputRef.current?.focus({ preventScroll: true });
    }
  }, [active]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active || submitting) {
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      await loginManagementAuth(usernameOrEmail, password);
      const searchParams = new URLSearchParams(window.location.search);
      router.push(searchParams.get('returnTo') || '/management');
      router.refresh();
    } catch (submitError) {
      setError(
        submitError instanceof Error
          ? submitError.message
          : '\u0110\u0103ng nh\u1eadp th\u1ea5t b\u1ea1i.',
      );
    } finally {
      setSubmitting(false);
    }
  }

  const disabled = !active || submitting;

  return (
    <form className={styles.authForm} onSubmit={handleSubmit} aria-hidden={!active}>
      <p className={styles.brandText}>SMART MEETING</p>
      <p className={styles.formLead}>{'Ch\u00e0o m\u1eebng tr\u1edf l\u1ea1i'}</p>
      <h1 className={styles.formTitle}>{'\u0110\u0103ng nh\u1eadp'}</h1>
      <p className={styles.formDescription}>
        {
          '\u0110\u0103ng nh\u1eadp \u0111\u1ec3 ti\u1ebfp t\u1ee5c qu\u1ea3n l\u00fd cu\u1ed9c h\u1ecdp.'
        }
      </p>

      <div className={styles.fieldGroup}>
        <label htmlFor="login-username">
          {'T\u00ean \u0111\u0103ng nh\u1eadp ho\u1eb7c email'}
        </label>
        <input
          ref={firstInputRef}
          id="login-username"
          className={styles.input}
          type="text"
          autoComplete="username"
          required
          value={usernameOrEmail}
          onChange={(event) => setUsernameOrEmail(event.target.value)}
          disabled={disabled}
        />
      </div>

      <div className={styles.fieldGroup}>
        <label htmlFor="login-password">{'M\u1eadt kh\u1ea9u'}</label>
        <div className={styles.passwordField}>
          <input
            id="login-password"
            className={styles.input}
            type={showPassword ? 'text' : 'password'}
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            disabled={disabled}
          />
          <button
            type="button"
            className={styles.passwordToggle}
            aria-label={
              showPassword ? '\u1ea8n m\u1eadt kh\u1ea9u' : 'Hi\u1ec7n m\u1eadt kh\u1ea9u'
            }
            aria-pressed={showPassword}
            onClick={() => setShowPassword((current) => !current)}
            disabled={disabled}
          >
            <EyeIcon hidden={showPassword} />
          </button>
        </div>
      </div>

      {error ? (
        <p className={styles.error} role="alert">
          {error}
        </p>
      ) : null}

      <button type="submit" className={styles.primaryButton} disabled={disabled}>
        {submitting ? '\u0110ang \u0111\u0103ng nh\u1eadp...' : '\u0110\u0103ng nh\u1eadp'}
      </button>

      <p className={styles.switchText}>
        {'Ch\u01b0a c\u00f3 t\u00e0i kho\u1ea3n? '}
        <button type="button" onClick={onRequestRegister} disabled={submitting || !active}>
          {'\u0110\u0103ng k\u00fd'}
        </button>
      </p>
    </form>
  );
}
