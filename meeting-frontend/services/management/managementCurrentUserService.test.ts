import { describe, expect, it } from 'vitest';
import { getManagementCurrentUser } from './managementCurrentUserService';

describe('getManagementCurrentUser', () => {
  it('returns null because session auth now comes from /api/auth/me', () => {
    expect(getManagementCurrentUser()).toBeNull();
  });
});
