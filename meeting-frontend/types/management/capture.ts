export type ManagementCaptureStatus =
  | 'NOT_STARTED'
  | 'STARTING'
  | 'RUNNING'
  | 'STOPPING'
  | 'COMPLETED'
  | 'FAILED';

export interface ManagementCaptureResponse {
  id: number | null;
  externalSessionId: string | null;
  status: ManagementCaptureStatus;
  startedAt: string | null;
  completedAt: string | null;
  manifestPath: string | null;
  errorMessage: string | null;
}
