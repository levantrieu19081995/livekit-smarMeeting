'use client';

import { useContext } from 'react';
import { ManagementAuthContext } from '@/contexts/ManagementAuthContext';

export function useManagementAuth() {
  const context = useContext(ManagementAuthContext);
  if (!context) {
    throw new Error('useManagementAuth must be used within ManagementAuthProvider');
  }
  return context;
}
