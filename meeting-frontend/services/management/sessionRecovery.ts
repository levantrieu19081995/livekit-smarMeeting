import { ManagementApiError } from './httpClient';

export function isRejectedSession(failure: unknown) {
  return failure instanceof ManagementApiError && [401, 403].includes(failure.status);
}

// Logout and superseding refreshes invalidate earlier authentication receipts.
export class SessionRequestGate {
  private revision = 0;
  private active = true;
  private loggingOut = false;
  activate() {
    this.active = true;
    this.loggingOut = false;
  }
  dispose() {
    this.active = false;
    this.revision++;
  }
  refresh() {
    return this.active && !this.loggingOut ? ++this.revision : null;
  }
  logout() {
    if (!this.active || this.loggingOut) return null;
    this.loggingOut = true;
    return ++this.revision;
  }
  current(revision: number) {
    return this.active && this.revision === revision;
  }
  finishLogout(revision: number) {
    if (this.revision === revision) this.loggingOut = false;
  }
}
