import { describe, expect, it } from 'vitest';
import { ManagementApiError, ManagementTransportError } from './httpClient';
import { SessionRequestGate, isRejectedSession } from './sessionRecovery';

describe('session recovery and late receipts', () => {
  it('distinguishes a temporary outage from rejected credentials', () => {
    expect(isRejectedSession(new ManagementTransportError('NETWORK_UNAVAILABLE', false))).toBe(
      false,
    );
    expect(isRejectedSession(new ManagementApiError('Unavailable', 503))).toBe(false);
    expect(isRejectedSession(new ManagementApiError('Expired', 401))).toBe(true);
    expect(isRejectedSession(new ManagementApiError('Disabled', 403))).toBe(true);
  });
  it('refuses an old successful authentication receipt after logout starts', () => {
    const gate = new SessionRequestGate();
    const old = gate.refresh()!;
    const logout = gate.logout()!;
    expect(gate.current(old)).toBe(false);
    expect(gate.refresh()).toBeNull();
    expect(gate.current(logout)).toBe(true);
    gate.finishLogout(logout);
    expect(gate.refresh()).not.toBeNull();
  });
  it('lets the newest session refresh win and discards receipts after unmount', () => {
    const gate = new SessionRequestGate();
    const old = gate.refresh()!;
    const newer = gate.refresh()!;
    expect(gate.current(old)).toBe(false);
    expect(gate.current(newer)).toBe(true);
    gate.dispose();
    expect(gate.current(newer)).toBe(false);
    expect(gate.refresh()).toBeNull();
  });
  it('does not let a late old logout unlock a newer logout after remount', () => {
    const gate = new SessionRequestGate();
    const old = gate.logout()!;
    gate.dispose();
    gate.activate();
    const newer = gate.logout()!;
    gate.finishLogout(old);
    expect(gate.refresh()).toBeNull();
    gate.finishLogout(newer);
    expect(gate.refresh()).not.toBeNull();
  });
});
