export interface ManagementUser {
  publicId: string;
  username: string | null;
  fullName: string;
  email: string;
  avatarUrl: string | null;
  accountStatus: 'PENDING_APPROVAL' | 'ACTIVE' | 'DISABLED';
  systemRole: 'ADMIN' | 'MEETING_MANAGER' | 'USER';
  emailVerified: boolean;
}

export interface ManagementDirectoryUser {
  publicId: string;
  username: string | null;
  fullName: string;
  email: string;
  avatarUrl: string | null;
}
