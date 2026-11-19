import { MinutesDetailPage } from '@/components/management/minutes/MinutesDetailPage';

export default async function ManagementMinutesDetailRoute(props: {
  params: Promise<{ meetingId: string }>;
  searchParams: Promise<{ tab?: string; section?: string }>;
}) {
  const params = await props.params;
  const searchParams = await props.searchParams;
  return (
    <MinutesDetailPage
      meetingId={params.meetingId}
      initialTab={searchParams.tab}
      initialSection={searchParams.section}
    />
  );
}
