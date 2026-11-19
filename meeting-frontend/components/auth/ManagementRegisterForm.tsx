'use client';

import { FormEvent, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { registerManagementAuth } from '@/services/management/managementAuthService';
import styles from '@/styles/management/AuthPages.module.css';

interface ManagementRegisterFormProps {
  active: boolean;
  onRequestLogin: () => void;
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

export function ManagementRegisterForm({ active, onRequestLogin }: ManagementRegisterFormProps) {
  const router = useRouter();
  const firstInputRef = useRef<HTMLInputElement>(null);
  const [form, setForm] = useState({ username: '', fullName: '', email: '', password: '' });
  const [confirmPassword, setConfirmPassword] = useState('');
  const [acceptedTerms, setAcceptedTerms] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (active) {
      firstInputRef.current?.focus({ preventScroll: true });
    }
  }, [active]);

  function updateForm(field: keyof typeof form, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!active || submitting) {
      return;
    }

    setError(null);
    setMessage(null);

    const trimmedForm = {
      fullName: form.fullName.trim(),
      username: form.username.trim(),
      email: form.email.trim(),
      password: form.password,
    };

    if (
      !trimmedForm.fullName ||
      !trimmedForm.username ||
      !trimmedForm.email ||
      !trimmedForm.password ||
      !confirmPassword
    ) {
      setError(
        'Vui l\u00f2ng nh\u1eadp \u0111\u1ea7y \u0111\u1ee7 th\u00f4ng tin b\u1eaft bu\u1ed9c.',
      );
      return;
    }

    if (trimmedForm.password !== confirmPassword) {
      setError('M\u1eadt kh\u1ea9u x\u00e1c nh\u1eadn kh\u00f4ng kh\u1edbp.');
      return;
    }

    if (!acceptedTerms) {
      setError(
        'B\u1ea1n c\u1ea7n \u0111\u1ed3ng \u00fd v\u1edbi \u0110i\u1ec1u kho\u1ea3n s\u1eed d\u1ee5ng v\u00e0 Ch\u00ednh s\u00e1ch b\u1ea3o m\u1eadt.',
      );
      return;
    }

    setSubmitting(true);
    try {
      const response = await registerManagementAuth(trimmedForm);
      if (response.authenticated) {
        const searchParams = new URLSearchParams(window.location.search);
        router.push(searchParams.get('returnTo') || '/management');
        router.refresh();
      } else {
        setMessage(
          'T\u00e0i kho\u1ea3n \u0111\u00e3 \u0111\u01b0\u1ee3c t\u1ea1o. Vui l\u00f2ng ch\u1edd qu\u1ea3n tr\u1ecb vi\u00ean k\u00edch ho\u1ea1t.',
        );
      }
    } catch (submitError) {
      setError(
        submitError instanceof Error
          ? submitError.message
          : '\u0110\u0103ng k\u00fd th\u1ea5t b\u1ea1i.',
      );
    } finally {
      setSubmitting(false);
    }
  }

  const disabled = !active || submitting;

  return (
    <form
      className={`${styles.authForm} ${styles.registerForm}`}
      onSubmit={handleSubmit}
      aria-hidden={!active}
    >
      <p className={styles.brandText}>SMART MEETING</p>
      <p className={styles.formLead}>{'B\u1eaft \u0111\u1ea7u ngay'}</p>
      <h1 className={styles.formTitle}>{'T\u1ea1o t\u00e0i kho\u1ea3n'}</h1>
      <p className={styles.formDescription}>
        {
          'T\u1ea1o t\u00e0i kho\u1ea3n \u0111\u1ec3 qu\u1ea3n l\u00fd v\u00e0 tham gia cu\u1ed9c h\u1ecdp.'
        }
      </p>

      <div className={styles.fieldGroup}>
        <label htmlFor="register-full-name">{'H\u1ecd v\u00e0 t\u00ean'}</label>
        <input
          ref={firstInputRef}
          id="register-full-name"
          className={styles.input}
          type="text"
          autoComplete="name"
          required
          value={form.fullName}
          onChange={(event) => updateForm('fullName', event.target.value)}
          disabled={disabled}
        />
      </div>

      <div className={styles.fieldGroup}>
        <label htmlFor="register-username">{'T\u00ean \u0111\u0103ng nh\u1eadp'}</label>
        <input
          id="register-username"
          className={styles.input}
          type="text"
          autoComplete="username"
          required
          value={form.username}
          onChange={(event) => updateForm('username', event.target.value)}
          disabled={disabled}
        />
      </div>

      <div className={styles.fieldGroup}>
        <label htmlFor="register-email">Email</label>
        <input
          id="register-email"
          className={styles.input}
          type="email"
          autoComplete="email"
          required
          value={form.email}
          onChange={(event) => updateForm('email', event.target.value)}
          disabled={disabled}
        />
      </div>

      <div className={styles.fieldGroup}>
        <label htmlFor="register-password">{'M\u1eadt kh\u1ea9u'}</label>
        <div className={styles.passwordField}>
          <input
            id="register-password"
            className={styles.input}
            type={showPassword ? 'text' : 'password'}
            autoComplete="new-password"
            required
            value={form.password}
            onChange={(event) => updateForm('password', event.target.value)}
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

      <div className={styles.fieldGroup}>
        <label htmlFor="register-confirm-password">{'X\u00e1c nh\u1eadn m\u1eadt kh\u1ea9u'}</label>
        <div className={styles.passwordField}>
          <input
            id="register-confirm-password"
            className={styles.input}
            type={showConfirmPassword ? 'text' : 'password'}
            autoComplete="new-password"
            required
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
            disabled={disabled}
          />
          <button
            type="button"
            className={styles.passwordToggle}
            aria-label={
              showConfirmPassword
                ? '\u1ea8n m\u1eadt kh\u1ea9u x\u00e1c nh\u1eadn'
                : 'Hi\u1ec7n m\u1eadt kh\u1ea9u x\u00e1c nh\u1eadn'
            }
            aria-pressed={showConfirmPassword}
            onClick={() => setShowConfirmPassword((current) => !current)}
            disabled={disabled}
          >
            <EyeIcon hidden={showConfirmPassword} />
          </button>
        </div>
      </div>

      <label className={styles.termsRow}>
        <input
          type="checkbox"
          checked={acceptedTerms}
          onChange={(event) => setAcceptedTerms(event.target.checked)}
          disabled={disabled}
        />
        <span>
          {
            'T\u00f4i \u0111\u1ed3ng \u00fd v\u1edbi \u0110i\u1ec1u kho\u1ea3n s\u1eed d\u1ee5ng v\u00e0 Ch\u00ednh s\u00e1ch b\u1ea3o m\u1eadt'
          }
        </span>
      </label>

      {error ? (
        <p className={styles.error} role="alert">
          {error}
        </p>
      ) : null}

      {message ? (
        <div className={styles.message} role="status">
          <p>{message}</p>
          <button type="button" onClick={onRequestLogin} disabled={!active || submitting}>
            {'Quay l\u1ea1i \u0111\u0103ng nh\u1eadp'}
          </button>
        </div>
      ) : null}

      <button type="submit" className={styles.primaryButton} disabled={disabled}>
        {submitting ? '\u0110ang \u0111\u0103ng k\u00fd...' : '\u0110\u0103ng k\u00fd'}
      </button>

      <p className={styles.switchText}>
        {'\u0110\u00e3 c\u00f3 t\u00e0i kho\u1ea3n? '}
        <button type="button" onClick={onRequestLogin} disabled={submitting || !active}>
          {'\u0110\u0103ng nh\u1eadp'}
        </button>
      </p>
    </form>
  );
}
