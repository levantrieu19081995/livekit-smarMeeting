'use client';

import { createPortal } from 'react-dom';
import { useEffect, useRef, useState } from 'react';
import { searchManagementDirectory } from '@/services/management/managementUserService';
import {
  getDefaultMeetingParticipantRole,
  getMeetingParticipantRoleOptions,
  type MeetingParticipantMode,
} from '@/lib/meetingParticipantRules';
import type {
  ManagementMeetingAccessType,
  ManagementMeetingParticipant,
  ManagementMeetingRole,
} from '@/types/management/meeting';
import { MANAGEMENT_MEETING_ROLE_LABELS } from '@/types/management/meeting';
import type { ManagementDirectoryUser } from '@/types/management/user';
import styles from '@/styles/management/MeetingPages.module.css';

export interface ParticipantPickerSelection {
  user: ManagementDirectoryUser;
  role: ManagementMeetingRole;
}

interface ParticipantPickerDialogProps {
  className?: string;
  open: boolean;
  accessType: ManagementMeetingAccessType;
  mode?: MeetingParticipantMode;
  existingParticipants: ManagementMeetingParticipant[];
  onCancel: () => void;
  onConfirm: (selections: ParticipantPickerSelection[]) => void;
}

function getUserInitial(name: string) {
  return name.trim().charAt(0).toUpperCase() || '?';
}

export function ParticipantPickerDialog({
  className,
  open,
  accessType,
  mode = 'create',
  existingParticipants,
  onCancel,
  onConfirm,
}: ParticipantPickerDialogProps) {
  const [portalReady, setPortalReady] = useState(false);
  const [query, setQuery] = useState('');
  const [users, setUsers] = useState<ManagementDirectoryUser[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selections, setSelections] = useState<ParticipantPickerSelection[]>([]);
  const requestIdRef = useRef(0);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const dialogRef = useRef<HTMLElement>(null);

  const availableRoleOptions = getMeetingParticipantRoleOptions(accessType, mode);
  const description =
    accessType === 'PRIVATE'
      ? 'Chọn những tài khoản được phép tham gia phòng riêng tư.'
      : mode === 'edit'
        ? 'Chọn người tham gia với vai trò chủ tọa, thư ký hoặc thành viên. Người mới mặc định là thành viên khi cuộc họp đã có chủ tọa.'
        : 'Chọn chủ tọa, thư ký và thành viên. Chỉ được có một chủ tọa và tối đa một thư ký. Người khác vẫn có thể tham gia bằng liên kết.';

  useEffect(() => {
    setPortalReady(true);
    return () => setPortalReady(false);
  }, []);

  useEffect(() => {
    if (!open) {
      return;
    }

    setQuery('');
    setSelections([]);
    setError(null);
    window.setTimeout(() => searchInputRef.current?.focus(), 0);
  }, [open]);

  useEffect(() => {
    if (!open) {
      return;
    }

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [open]);

  useEffect(() => {
    if (!open) {
      return;
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault();
        handleCancel();
        return;
      }

      if (event.key !== 'Tab' || !dialogRef.current) {
        return;
      }

      const focusableElements = Array.from(
        dialogRef.current.querySelectorAll<HTMLElement>(
          'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])',
        ),
      );

      if (focusableElements.length === 0) {
        return;
      }

      const firstElement = focusableElements[0];
      const lastElement = focusableElements[focusableElements.length - 1];

      if (event.shiftKey && document.activeElement === firstElement) {
        event.preventDefault();
        lastElement.focus();
      } else if (!event.shiftKey && document.activeElement === lastElement) {
        event.preventDefault();
        firstElement.focus();
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, selections]);

  useEffect(() => {
    if (!open) {
      return;
    }

    let active = true;
    const requestId = requestIdRef.current + 1;
    requestIdRef.current = requestId;
    setLoading(true);
    setError(null);

    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const result = await searchManagementDirectory(query.trim());
          if (!active || requestIdRef.current !== requestId) {
            return;
          }
          setUsers(result);
        } catch (searchError) {
          if (!active || requestIdRef.current !== requestId) {
            return;
          }
          setUsers([]);
          setError(
            searchError instanceof Error
              ? searchError.message
              : 'Không thể tải danh sách người dùng.',
          );
        } finally {
          if (active && requestIdRef.current === requestId) {
            setLoading(false);
          }
        }
      })();
    }, 300);

    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [open, query]);

  function resetDialogState() {
    setQuery('');
    setSelections([]);
    setError(null);
  }

  function handleCancel() {
    resetDialogState();
    onCancel();
  }

  function isExistingUser(user: ManagementDirectoryUser) {
    return existingParticipants.some(
      (participant) =>
        participant.userId === user.publicId ||
        participant.email.toLowerCase() === user.email.toLowerCase(),
    );
  }

  function isSelectedUser(user: ManagementDirectoryUser) {
    return selections.some((selection) => selection.user.publicId === user.publicId);
  }

  function toggleSelection(user: ManagementDirectoryUser) {
    if (isExistingUser(user)) {
      return;
    }

    setSelections((current) => {
      if (current.some((selection) => selection.user.publicId === user.publicId)) {
        return current.filter((selection) => selection.user.publicId !== user.publicId);
      }

      return [
        ...current,
        {
          user,
          role: getDefaultMeetingParticipantRole(
            accessType,
            [
              ...existingParticipants.map((participant) => participant.role),
              ...current.map((selection) => selection.role),
            ],
            mode,
          ),
        },
      ];
    });
    setError(null);
  }

  function updateSelectionRole(userPublicId: string, nextRole: ManagementMeetingRole) {
    if (nextRole === 'CHAIRMAN' || (mode === 'create' && nextRole === 'SECRETARY')) {
      const existingRole = existingParticipants.some(
        (participant) => participant.role === nextRole,
      );
      const otherSelectionRole = selections.some(
        (selection) => selection.role === nextRole && selection.user.publicId !== userPublicId,
      );

      if (existingRole || otherSelectionRole) {
        setError(
          nextRole === 'CHAIRMAN'
            ? 'Cuộc họp chỉ được có một chủ tọa.'
            : 'Cuộc họp chỉ được có một thư ký.',
        );
        return;
      }
    }

    if (!availableRoleOptions.includes(nextRole)) {
      setError('Vui lòng chọn vai trò trong danh sách.');
      return;
    }

    setSelections((current) =>
      current.map((selection) =>
        selection.user.publicId === userPublicId ? { ...selection, role: nextRole } : selection,
      ),
    );
    setError(null);
  }

  function handleConfirm() {
    if (selections.length === 0) {
      setError('Vui lòng chọn ít nhất một người.');
      return;
    }

    const totalChairmen =
      existingParticipants.filter((participant) => participant.role === 'CHAIRMAN').length +
      selections.filter((selection) => selection.role === 'CHAIRMAN').length;

    if (totalChairmen === 0) {
      setError('Vui lòng chọn một chủ tọa cho cuộc họp.');
      return;
    }

    if (totalChairmen > 1) {
      setError('Cuộc họp chỉ được có một chủ tọa.');
      return;
    }

    const totalSecretaries =
      existingParticipants.filter((participant) => participant.role === 'SECRETARY').length +
      selections.filter((selection) => selection.role === 'SECRETARY').length;
    if (mode === 'create' && totalSecretaries > 1) {
      setError('Cuộc họp chỉ được có một thư ký.');
      return;
    }

    if (selections.some((selection) => !availableRoleOptions.includes(selection.role))) {
      setError('Vui lòng chọn vai trò trong danh sách.');
      return;
    }

    onConfirm(selections);
    resetDialogState();
  }

  if (!portalReady || !open) {
    return null;
  }

  return createPortal(
    <div
      className={`${styles.participantModalOverlay} ${className ?? ''}`}
      onMouseDown={handleCancel}
    >
      <section
        ref={dialogRef}
        className={styles.participantModal}
        role="dialog"
        aria-modal="true"
        aria-labelledby="participant-picker-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className={styles.participantModalHeader}>
          <div>
            <p className={styles.eyebrow}>Người tham gia</p>
            <h2 id="participant-picker-title">Chọn người tham gia</h2>
            <p>{description}</p>
          </div>

          <button
            type="button"
            className={styles.participantModalClose}
            aria-label="Đóng"
            onClick={handleCancel}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="m6.4 5 12.6 12.6-1.4 1.4L5 6.4 6.4 5Zm12.6 1.4L6.4 19 5 17.6 17.6 5 19 6.4Z" />
            </svg>
          </button>
        </header>

        <div className={styles.participantModalSearch}>
          <label className={styles.field}>
            <span className={styles.fieldLabel}>Tìm người dùng</span>
            <input
              ref={searchInputRef}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Nhập tên, username hoặc email"
              autoComplete="off"
            />
          </label>
        </div>

        <div className={styles.participantModalBody}>
          {error ? (
            <p className={styles.formError} role="alert" aria-live="polite">
              {error}
            </p>
          ) : null}

          <div className={styles.participantModalSelection}>
            <strong>Đã chọn {selections.length} người</strong>
            {selections.length > 0 ? (
              <div className={styles.participantModalSelectionList}>
                {selections.map((selection) => (
                  <span
                    key={selection.user.publicId}
                    className={styles.participantModalSelectionItem}
                  >
                    {selection.user.fullName} — {MANAGEMENT_MEETING_ROLE_LABELS[selection.role]}
                    <button
                      type="button"
                      aria-label={`Bỏ ${selection.user.fullName}`}
                      onClick={() => toggleSelection(selection.user)}
                    >
                      ×
                    </button>
                  </span>
                ))}
              </div>
            ) : null}
          </div>

          <div className={styles.participantModalResults}>
            {loading ? (
              <p className={styles.directoryLoading} aria-live="polite">
                Đang tải danh sách người dùng...
              </p>
            ) : null}

            {!loading && users.length === 0 ? (
              <p className={styles.directoryEmpty}>Không tìm thấy người dùng phù hợp</p>
            ) : null}

            {users.map((user) => {
              const existing = isExistingUser(user);
              const selected = isSelectedUser(user);
              const selection = selections.find((item) => item.user.publicId === user.publicId);

              return (
                <div
                  key={user.publicId}
                  className={`${styles.participantModalResult} ${
                    selected ? styles.participantModalResultSelected : ''
                  } ${existing ? styles.participantModalResultDisabled : ''}`}
                  role="option"
                  aria-selected={selected}
                >
                  <label className={styles.participantCheckbox}>
                    <input
                      type="checkbox"
                      checked={selected}
                      disabled={existing}
                      onChange={() => toggleSelection(user)}
                    />
                    <span className={styles.visuallyHidden}>
                      {selected ? 'Bỏ chọn' : 'Chọn'} {user.fullName}
                    </span>
                  </label>

                  <button
                    type="button"
                    className={styles.participantModalResultButton}
                    disabled={existing}
                    onClick={() => toggleSelection(user)}
                  >
                    <span className={styles.directoryAvatar} aria-hidden="true">
                      {user.avatarUrl ? (
                        <span
                          className={styles.directoryAvatarImage}
                          style={{ backgroundImage: `url("${user.avatarUrl}")` }}
                        />
                      ) : (
                        getUserInitial(user.fullName)
                      )}
                    </span>
                    <span className={styles.directoryUserInfo}>
                      <strong className={styles.directoryUserName}>{user.fullName}</strong>
                      <span className={styles.directoryUserMeta}>
                        {user.email}
                        {user.username ? ` • ${user.username}` : ''}
                      </span>
                    </span>
                    {existing ? <span className={styles.metaText}>Đã thêm</span> : null}
                  </button>

                  {selection ? (
                    <label className={styles.participantRoleSelect}>
                      <span>Vai trò</span>
                      <select
                        value={selection.role}
                        onChange={(event) =>
                          updateSelectionRole(
                            user.publicId,
                            event.target.value as ManagementMeetingRole,
                          )
                        }
                      >
                        {availableRoleOptions.map((role) => (
                          <option key={role} value={role}>
                            {MANAGEMENT_MEETING_ROLE_LABELS[role]}
                          </option>
                        ))}
                      </select>
                    </label>
                  ) : null}
                </div>
              );
            })}
          </div>
        </div>

        <footer className={styles.participantModalFooter}>
          <span>Đã chọn {selections.length} người</span>

          <div className={styles.participantModalFooterActions}>
            <button type="button" className={styles.secondaryButton} onClick={handleCancel}>
              Hủy
            </button>
            <button type="button" className={styles.primaryButton} onClick={handleConfirm}>
              Xác nhận
            </button>
          </div>
        </footer>
      </section>
    </div>,
    document.body,
  );
}
