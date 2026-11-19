'use client';

import { HomePageClient } from '@/components/home/HomePageClient';
import { ManagementAuthProvider } from '@/contexts/ManagementAuthContext';

export default function Page() {
  return (
    <ManagementAuthProvider>
      <HomePageClient />
    </ManagementAuthProvider>
  );
}
