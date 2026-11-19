import { describe, expect, it, vi } from 'vitest';
import { QnaRefreshScheduler } from './qnaRefreshScheduler';

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
async function flush() {
  await Promise.resolve();
  await Promise.resolve();
}

describe('Q&A refresh coalescing', () => {
  it('does not interrupt or multiply a slow GET on repeated periodic ticks', async () => {
    const slow = deferred();
    const refresh = vi.fn().mockReturnValueOnce(slow.promise).mockResolvedValue(undefined);
    const scheduler = new QnaRefreshScheduler(refresh, () => true);
    scheduler.request();
    scheduler.poll();
    scheduler.poll();
    scheduler.poll();
    expect(refresh).toHaveBeenCalledOnce();
    slow.resolve();
    await flush();
    expect(refresh).toHaveBeenCalledOnce();
    scheduler.poll();
    await flush();
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it('retains only one trailing read for many hints received during a pending GET', async () => {
    const slow = deferred();
    const refresh = vi.fn().mockReturnValueOnce(slow.promise).mockResolvedValue(undefined);
    const scheduler = new QnaRefreshScheduler(refresh, () => true);
    scheduler.request();
    scheduler.request();
    scheduler.request();
    scheduler.request();
    slow.resolve();
    await flush();
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it('preserves busy-write hints until the mutation finishes and refreshes the current query', async () => {
    let busy = true;
    let query = 'old';
    const loaded: string[] = [];
    const refresh = vi.fn(async () => {
      loaded.push(query);
    });
    const scheduler = new QnaRefreshScheduler(refresh, () => !busy);
    scheduler.request();
    scheduler.request();
    scheduler.poll();
    expect(refresh).not.toHaveBeenCalled();
    query = 'new';
    busy = false;
    scheduler.request();
    await flush();
    expect(loaded).toEqual(['new']);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it('does no periodic work while hidden and reloads once when visible again', async () => {
    let visible = false;
    const refresh = vi.fn().mockResolvedValue(undefined);
    const scheduler = new QnaRefreshScheduler(refresh, () => visible);
    scheduler.poll();
    scheduler.request();
    scheduler.poll();
    expect(refresh).not.toHaveBeenCalled();
    visible = true;
    scheduler.request();
    await flush();
    expect(refresh).toHaveBeenCalledOnce();
  });

  it('never invokes a trailing refresh or reports an error after disposal', async () => {
    let reject!: (failure: Error) => void;
    const promise = new Promise<void>((_, no) => {
      reject = no;
    });
    const refresh = vi.fn().mockReturnValue(promise);
    const failure = vi.fn();
    const scheduler = new QnaRefreshScheduler(refresh, () => true, failure);
    scheduler.request();
    scheduler.request();
    scheduler.dispose();
    reject(new Error('Unmounted'));
    await flush();
    scheduler.request();
    scheduler.poll();
    expect(refresh).toHaveBeenCalledOnce();
    expect(failure).not.toHaveBeenCalled();
  });

  it('reports a current failure and allows recovery on the next wake-up', async () => {
    const failure = vi.fn();
    const refresh = vi
      .fn()
      .mockRejectedValueOnce(new Error('Read failed'))
      .mockResolvedValue(undefined);
    const scheduler = new QnaRefreshScheduler(refresh, () => true, failure);
    scheduler.request();
    await flush();
    expect(failure).toHaveBeenCalledOnce();
    scheduler.request();
    await flush();
    expect(refresh).toHaveBeenCalledTimes(2);
  });
});
