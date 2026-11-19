'use client';

import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import { MeetingForm, type MeetingFormValues } from '@/components/meetings/MeetingForm';
import {
  createManagementMeeting,
  getMeetingCreationFingerprint,
} from '@/services/management/managementMeetingApi';
import {
  getMeetingCreationRequestId,
  confirmMeetingCreationRequest,
  readPendingMeetingCreation,
  type PointCreationDraft,
} from '@/services/management/pointCreationDraft';
import { recoverMeetingCreationInput } from '@/services/management/pointCreationRecovery';
import { useManagementAuth } from '@/hooks/useManagementAuth';
import { ManagementApiError } from '@/services/management/httpClient';
import type { ManagementMeeting } from '@/types/management/meeting';
import { CreatedMeetingDialog } from './CreatedMeetingDialog';
import { QuickMeetingForm } from './QuickMeetingForm';
import { MeetingCreateMenu } from './MeetingCreateMenu';
import styles from '@/styles/management/MeetingCreation.module.css';

export function MeetingCreatePage({ initialMode }: { initialMode?: string }) {
  const router = useRouter();
  const { user, authenticated, loading: authLoading } = useManagementAuth();
  const mode = initialMode;
  const busy = useRef(false);
  const [submitting, setSubmitting] = useState(false);
  const [created, setCreated] = useState<ManagementMeeting | null>(null);
  const [createdScope, setCreatedScope] = useState<string | null>(null);
  const [pendingDraft, setPendingDraft] = useState<PointCreationDraft | null>(null);
  const [acknowledgedDraftId, setAcknowledgedDraftId] = useState<string | null>(null);
  const [recoveredValues, setRecoveredValues] = useState<{
    scope: string;
    values: MeetingFormValues;
  } | null>(null);
  const [formVersion, setFormVersion] = useState(0);
  const active = useRef(false);
  const generation = useRef(0);
  const accountPublicId = !authLoading && authenticated && user ? user.publicId : null;
  const scope = `${accountPublicId ?? ''}:${mode ?? ''}`;
  const latestScope = useRef(scope);
  latestScope.current = scope;

  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
      generation.current += 1;
    };
  }, []);

  useEffect(() => {
    generation.current += 1;
    busy.current = false;
    setSubmitting(false);
    setCreated(null);
    setRecoveredValues(null);
    setAcknowledgedDraftId(null);
    setPendingDraft(
      mode && accountPublicId ? readPendingMeetingCreation(accountPublicId, mode) : null,
    );
  }, [scope, mode, accountPublicId]);

  function acknowledgePending(restore: boolean) {
    if (!accountPublicId || !pendingDraft) return;
    if (!mode) return;
    const current = readPendingMeetingCreation(accountPublicId, mode);
    if (current?.clientRequestId !== pendingDraft.clientRequestId) return;
    if (restore) {
      const restored = recoverMeetingCreationInput(
        current.fingerprint,
        mode === 'POINT' ? 'POINT' : mode === 'LATER' ? 'LATER' : 'SCHEDULED',
      );
      if (!restored) {
        toast.error('Không thể khôi phục đầy đủ thông tin. Bạn có thể chọn nhập thông tin mới.');
        return;
      }
      setRecoveredValues({ scope, values: restored });
      setFormVersion((value) => value + 1);
    }
    setAcknowledgedDraftId(current.clientRequestId);
  }

  async function handleSubmit(values: MeetingFormValues) {
    if (busy.current) return;
    busy.current = true;
    const requestGeneration = generation.current;
    const requestScope = scope;
    let pendingCreation: { accountPublicId: string; clientRequestId: string } | null = null;
    try {
      setSubmitting(true);
      const input: MeetingFormValues = {
        ...values,
        accessType:
          mode === 'POINT' ? 'PUBLIC' : mode === 'PRIVATE' ? 'PRIVATE' : values.accessType,
        creationMode: mode === 'POINT' ? 'POINT' : (values.creationMode ?? 'SCHEDULED'),
      };
      if (mode) {
        if (!authenticated || !user)
          throw new Error('Vui lòng đăng nhập lại trước khi tạo cuộc họp.');
        const previous = readPendingMeetingCreation(user.publicId, mode);
        if (previous && previous.clientRequestId !== acknowledgedDraftId) {
          setPendingDraft(previous);
          throw new Error(
            'Lần tạo trước chưa được xác nhận. Hãy khôi phục thông tin hoặc chọn nhập thông tin mới trước khi lưu.',
          );
        }
        input.clientRequestId = getMeetingCreationRequestId(
          user.publicId,
          mode,
          getMeetingCreationFingerprint(input),
        );
        pendingCreation = {
          accountPublicId: user.publicId,
          clientRequestId: input.clientRequestId,
        };
        setAcknowledgedDraftId(input.clientRequestId);
      }
      const createdMeeting = await createManagementMeeting(input);
      if (mode && user && input.clientRequestId) {
        confirmMeetingCreationRequest(user.publicId, mode, input.clientRequestId);
      }
      if (
        !active.current ||
        generation.current !== requestGeneration ||
        latestScope.current !== requestScope
      )
        return;
      setPendingDraft(null);
      setAcknowledgedDraftId(null);
      if (createdMeeting.documentUploadFailed) {
        toast.error('Cuộc họp đã được tạo nhưng một số tài liệu tải lên không thành công.');
      } else {
        toast.success('Đã tạo cuộc họp thành công.');
      }
      setCreated(createdMeeting);
      setCreatedScope(requestScope);
    } catch (error) {
      if (
        pendingCreation &&
        error instanceof ManagementApiError &&
        error.status === 410 &&
        error.code === 'MEETING_CREATION_RECEIPT_DELETED'
      ) {
        confirmMeetingCreationRequest(
          pendingCreation.accountPublicId,
          mode!,
          pendingCreation.clientRequestId,
        );
        if (
          !active.current ||
          generation.current !== requestGeneration ||
          latestScope.current !== requestScope
        )
          return;
        setPendingDraft(null);
        setAcknowledgedDraftId(null);
        toast.error('Cuộc họp của lần tạo trước đã bị xóa. Nhấn Lưu lại để tạo cuộc họp mới.');
      } else {
        if (
          !active.current ||
          generation.current !== requestGeneration ||
          latestScope.current !== requestScope
        )
          return;
        if (mode && user) setPendingDraft(readPendingMeetingCreation(user.publicId, mode));
        toast.error(error instanceof Error ? error.message : 'Không thể tạo cuộc họp.');
      }
    } finally {
      if (
        active.current &&
        generation.current === requestGeneration &&
        latestScope.current === requestScope
      ) {
        busy.current = false;
        setSubmitting(false);
      }
    }
  }

  if (mode !== 'LATER' && mode !== 'PRIVATE' && mode !== 'POINT')
    return (
      <div className={styles.form}>
        <h1>Tạo cuộc họp</h1>
        <p>Chọn cuộc họp tức thì, sử dụng sau, riêng tư hoặc theo điểm cầu.</p>
        <MeetingCreateMenu />
      </div>
    );

  return (
    <>
      {pendingDraft ? (
        <section className={styles.recovery} aria-label="Lần tạo cuộc họp chưa xác nhận">
          <strong>Lần tạo trước chưa được xác nhận</strong>
          <p>
            Khôi phục thông tin và thử lại để lấy đúng liên kết. Tài liệu đã chọn trên thiết bị cần
            được chọn lại.
          </p>
          <p>
            Thay đổi thông tin sẽ tạo yêu cầu mới; cuộc họp của lần tạo trước có thể đã tồn tại.
          </p>
          <div className={styles.recoveryActions}>
            <button
              type="button"
              className={styles.secondary}
              disabled={submitting}
              onClick={() => acknowledgePending(true)}
            >
              Khôi phục thông tin
            </button>
            {acknowledgedDraftId !== pendingDraft.clientRequestId ? (
              <button
                type="button"
                className={styles.secondary}
                disabled={submitting}
                onClick={() => acknowledgePending(false)}
              >
                Nhập thông tin mới
              </button>
            ) : null}
          </div>
        </section>
      ) : null}
      {mode === 'LATER' ? (
        <QuickMeetingForm
          submitting={submitting}
          onSubmit={handleSubmit}
          initialValues={recoveredValues?.scope === scope ? recoveredValues.values : undefined}
          key={`${scope}:${formVersion}`}
          onCancel={() => router.push('/management/meetings')}
        />
      ) : (
        <MeetingForm
          key={`${scope}:${formVersion}`}
          mode="create"
          creationMode={mode === 'POINT' ? 'POINT' : 'SCHEDULED'}
          initialValues={recoveredValues?.scope === scope ? recoveredValues.values : undefined}
          submitting={submitting}
          onSubmit={handleSubmit}
          onCancel={() => router.push('/management/meetings')}
        />
      )}
      {created && createdScope === scope ? (
        <CreatedMeetingDialog
          meeting={created}
          onClose={() => {
            setCreated(null);
            router.push('/management/meetings');
          }}
        />
      ) : null}
    </>
  );
}
