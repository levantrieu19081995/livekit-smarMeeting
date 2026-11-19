// Coalesce transport hints around the existing request coordinator. Periodic
// checks never interrupt a slow read; meaningful hints retain one trailing read.
export class QnaRefreshScheduler {
  private active = true;
  private running = false;
  private queued = false;

  constructor(
    private readonly refresh: () => Promise<void>,
    private readonly canRefresh: () => boolean,
    private readonly onFailure?: (failure: unknown) => void,
  ) {}

  request(trailing = true) {
    if (!this.active) return;
    if (this.running || !this.canRefresh()) {
      if (trailing) this.queued = true;
      return;
    }
    this.queued = false;
    this.running = true;
    void this.run();
  }

  poll() {
    this.request(false);
  }

  dispose() {
    this.active = false;
    this.queued = false;
  }

  private async run() {
    try {
      await this.refresh();
    } catch (failure) {
      if (this.active) this.onFailure?.(failure);
    } finally {
      this.running = false;
      if (this.active && this.queued && this.canRefresh()) this.request(false);
    }
  }
}
