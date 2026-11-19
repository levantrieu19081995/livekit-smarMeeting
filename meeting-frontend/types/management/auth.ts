export interface ManagementAuthUser {
  publicId: string;
  username: string | null;
  fullName: string;
  email: string;
  avatarUrl: string | null;
  provider: 'LOCAL' | 'GOOGLE' | 'INTERNAL_SSO';
  systemRole: 'ADMIN' | 'MEETING_MANAGER' | 'USER';
}

export interface ManagementAuthMeResponse {
  authenticated: boolean;
  user: ManagementAuthUser | null;
}

export interface ManagementAuthProvidersResponse {
  localEnabled: boolean;
  registrationEnabled: boolean;
  googleEnabled: boolean;
  googleClientId: string;
  internalSsoEnabled: boolean;
}

export interface ManagementAuthSessionResponse {
  authenticated: boolean;
  user: ManagementAuthUser | null;
}
