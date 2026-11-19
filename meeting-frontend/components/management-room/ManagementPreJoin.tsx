'use client';

import { LocalUserChoices, PreJoin } from '@livekit/components-react';
import { useEffect, useState } from 'react';
import {
  getManagementMediaContextError,
  isRecoverableMediaDeviceError,
  normalizeCurrentMediaDeviceChoices,
} from '@/services/management/managementMediaDeviceService';
import type { ManagementCurrentUser } from '@/types/management/join';
import styles from '@/styles/management/ManagementRoom.module.css';

interface ManagementPreJoinProps {
  currentUser: ManagementCurrentUser;
  onSubmit: (choices: LocalUserChoices) => void;
  onError: (error: Error) => void;
}

export function ManagementPreJoin({ currentUser, onSubmit, onError }: ManagementPreJoinProps) {
  const [warning, setWarning] = useState<string | null>(null);

  useEffect(() => {
    const mediaContextError = getManagementMediaContextError();

    if (mediaContextError) {
      setWarning(mediaContextError);
    }
  }, []);

  async function handleSubmit(choices: LocalUserChoices) {
    const mediaContextError = getManagementMediaContextError();

    if (mediaContextError) {
      setWarning(
        `${mediaContextError} Bạn phải mở bằng HTTPS hoặc cấu hình Chrome cho phép origin này thì mới có thể bật microphone và lấy AudioFrame.`,
      );
      return;
    }

    if (!choices.audioEnabled) {
      setWarning(
        'Bạn phải bật microphone trước khi vào phòng để hệ thống có thể lấy AudioFrame.',
      );
      return;
    }

    const normalized = await normalizeCurrentMediaDeviceChoices({
      ...choices,
      username: currentUser.name,
      audioEnabled: true,
    });

    setWarning(normalized.warning ?? null);
    onSubmit(normalized.choices);
  }

  function handleError(error: Error) {
    if (isRecoverableMediaDeviceError(error)) {
      setWarning(
        'Thiết bị âm thanh hoặc hình ảnh chưa sẵn sàng. Bạn vẫn có thể thử vào phòng bằng microphone mặc định.',
      );
      return;
    }

    onError(error);
  }

  return (
    <div className={styles.preJoinWrap}>
      <div className={styles.preJoinCard}>
        <div className={styles.identityCard}>
          <span className={styles.identityLabel}>Người tham gia</span>
          <strong className={styles.identityName}>{currentUser.name}</strong>
          <p className={styles.identity}>
            {currentUser.kind === 'GUEST' ? 'Khách tham gia' : currentUser.email}
          </p>
        </div>

        {warning ? <div className={styles.deviceWarning}>{warning}</div> : null}

        <div className={styles.prejoinRoot}>
          <PreJoin
            defaults={{
              username: currentUser.name,
              videoEnabled: false,
              audioEnabled: true,
            }}
            persistUserChoices={false}
            userLabel={currentUser.name}
            joinLabel="Vào phòng họp"
            micLabel="Micro"
            camLabel="Camera"
            onSubmit={(choices) => void handleSubmit(choices)}
            onError={handleError}
          />
        </div>
      </div>
    </div>
  );
}
