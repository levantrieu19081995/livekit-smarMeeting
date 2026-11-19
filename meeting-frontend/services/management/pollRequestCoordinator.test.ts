import { afterEach, describe, expect, it, vi } from 'vitest';
import { PollRequestCoordinator, withPollDeadline } from './pollRequestCoordinator';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

describe('Poll refresh races', () => {
  afterEach(() => vi.useRealTimers());

  it('ignores an old GET completing after a newer GET', async () => {
    const gate = new PollRequestCoordinator();
    const old = deferred<number>();
    const publish = vi.fn();
    const fail = vi.fn();
    let oldSignal!: AbortSignal;
    const first = gate.run(
      (signal) => {
        oldSignal = signal;
        return old.promise;
      },
      publish,
      fail,
    );
    await gate.run(async () => 2, publish, fail);
    old.resolve(1);
    await first;
    expect(oldSignal.aborted).toBe(true);
    expect(publish.mock.calls).toEqual([[2]]);
    expect(fail).not.toHaveBeenCalled();
  });

  it('ignores an in-flight GET after a mutation or unmount invalidates it', async () => {
    const gate = new PollRequestCoordinator();
    const old = deferred<number>();
    const publish = vi.fn();
    const fail = vi.fn();
    const pending = gate.run(() => old.promise, publish, fail);
    gate.invalidate();
    old.reject(new Error('stale network error'));
    await pending;
    expect(publish).not.toHaveBeenCalled();
    expect(fail).not.toHaveBeenCalled();
    expect(gate.pending).toBe(false);
  });

  it('times out a hanging request and allows the next refresh', async () => {
    vi.useFakeTimers();
    const gate = new PollRequestCoordinator();
    const fail = vi.fn();
    const pending = gate.run(() => new Promise(() => {}), vi.fn(), fail, 100);
    await vi.advanceTimersByTimeAsync(101);
    await pending;
    expect(fail).toHaveBeenCalledOnce();
    expect(gate.pending).toBe(false);
    const publish = vi.fn();
    await gate.run(async () => 3, publish, fail);
    expect(publish).toHaveBeenCalledWith(3);
  });

  it('does not turn an ambiguous mutation timeout into a success', async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    const pending = withPollDeadline(() => new Promise(() => {}), controller, 100);
    const assertion = expect(pending).rejects.toThrow('tải lại');
    await vi.advanceTimersByTimeAsync(101);
    await assertion;
    expect(controller.signal.aborted).toBe(true);
  });
});
