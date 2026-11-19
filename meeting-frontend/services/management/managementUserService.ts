import { managementFetchJson } from '@/services/management/httpClient';
import type { ManagementDirectoryUser, ManagementUser } from '@/types/management/user';

interface BackendPageResponse<T> {
  content: T[];
}

export async function searchManagementUsers(query = ''): Promise<ManagementUser[]> {
  const qs = new URLSearchParams();
  if (query) qs.set('query', query);
  const response = await managementFetchJson<BackendPageResponse<ManagementUser>>(`/api/users?${qs.toString()}`);
  return response.content;
}

export async function activateManagementUser(publicId: string) {
  return managementFetchJson<ManagementUser>(`/api/users/${encodeURIComponent(publicId)}/activate`, {
    method: 'POST',
  });
}

export async function disableManagementUser(publicId: string) {
  return managementFetchJson<ManagementUser>(`/api/users/${encodeURIComponent(publicId)}/disable`, {
    method: 'POST',
  });
}

export async function searchManagementDirectory(query = ''): Promise<ManagementDirectoryUser[]> {
  const qs = new URLSearchParams();
  if (query) qs.set('query', query);
  const response = await managementFetchJson<BackendPageResponse<ManagementDirectoryUser>>(
    `/api/directory/users?${qs.toString()}`,
  );
  return response.content;
}
