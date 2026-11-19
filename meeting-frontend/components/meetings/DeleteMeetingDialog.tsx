import styles from '@/styles/management/MeetingPages.module.css';

interface DeleteMeetingDialogProps {
  meetingTitle: string;
  isDeleting: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

export function DeleteMeetingDialog(props: DeleteMeetingDialogProps) {
  return (
    <div className={styles.dialogOverlay} role="presentation">
      <div
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-meeting-title"
      >
        <h3 id="delete-meeting-title">Xóa cuộc họp</h3>
        <p>
          Bạn có chắc chắn muốn xóa cuộc họp <strong>{props.meetingTitle}</strong> không? Thao tác
          này sẽ gọi Java meeting-service để xóa dữ liệu nghiệp vụ và room LiveKit liên quan.
        </p>

        <div className={styles.dialogActions}>
          <button
            type="button"
            className={styles.secondaryButton}
            onClick={props.onCancel}
            disabled={props.isDeleting}
          >
            Hủy
          </button>
          <button
            type="button"
            className={styles.primaryButton}
            onClick={props.onConfirm}
            disabled={props.isDeleting}
          >
            {props.isDeleting ? 'Đang xóa...' : 'Xóa cuộc họp'}
          </button>
        </div>
      </div>
    </div>
  );
}
