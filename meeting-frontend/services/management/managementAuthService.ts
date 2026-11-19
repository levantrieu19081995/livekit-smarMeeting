import { managementFetchJson } from './httpClient';
import type {
  ManagementAuthMeResponse,
  ManagementAuthProvidersResponse,
  ManagementAuthSessionResponse,
} from '@/types/management/auth';

export async function getManagementAuthMe(): Promise<ManagementAuthMeResponse> {
  return managementFetchJson<ManagementAuthMeResponse>('/api/auth/me');
}

export async function getManagementAuthProviders(): Promise<ManagementAuthProvidersResponse> {
  return managementFetchJson<ManagementAuthProvidersResponse>('/api/auth/providers');
}

export async function loginManagementAuth(usernameOrEmail: string, password: string) {
  return managementFetchJson<ManagementAuthSessionResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ usernameOrEmail, password }),
  });
}

export async function registerManagementAuth(payload: {
  username: string;
  fullName: string;
  email: string;
  password: string;
}) {
  return managementFetchJson<ManagementAuthSessionResponse>('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export async function loginManagementWithGoogle(credential: string) {
  return managementFetchJson<ManagementAuthSessionResponse>('/api/auth/google', {
    method: 'POST',
    body: JSON.stringify({ credential }),
  });
}

export async function logoutManagementAuth() {
  await managementFetchJson<void>('/api/auth/logout', {
    method: 'POST',
  });
}
