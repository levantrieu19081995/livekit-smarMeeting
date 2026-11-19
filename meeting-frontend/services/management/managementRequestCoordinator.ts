// Each refresh publishes one consistent response batch. Superseded or disposed
// requests cannot overwrite a confirmed mutation or a different meeting.
export class ManagementRequestCoordinator {
  private revision = 0;
  private controller: AbortController | null = null;
  get pending() {
    return this.controller !== null;
  }

  invalidate() {
    this.revision++;
    this.controller?.abort();
    this.controller = null;
  }

  async run<T>(
    load: (signal: AbortSignal) => Promise<T>,
    publish: (value: T) => void,
    fail: (error: unknown) => void,
    timeoutMs = 12000,
  ) {
    this.invalidate();
    const revision = this.revision;
    const controller = new AbortController();
    this.controller = controller;
    try {
      const value = await withManagementDeadline(load, controller, timeoutMs);
      if (revision === this.revision) publish(value);
    } catch (error) {
      if (revision === this.revision) fail(error);
    } finally {
      if (revision === this.revision) this.controller = null;
    }
  }
}

export async function withManagementDeadline<T>(
  operation: (signal: AbortSignal) => Promise<T>,
  controller: AbortController,
  timeoutMs = 30000,
): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      operation(controller.signal),
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => {
          controller.abort();
          reject(
            new Error('Máy chủ chưa xác nhận kịp thời. Hãy tải lại để kiểm tra trước khi thử lại.'),
          );
        }, timeoutMs);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}
