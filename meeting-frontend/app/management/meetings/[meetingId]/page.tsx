import { MeetingDetailPage } from '@/components/meetings/MeetingDetailPage';

export default async function MeetingDetailRoute(props: {
  params: Promise<{ meetingId: string }>;
  searchParams: Promise<{ mode?: string }>;
}) {
  const params = await props.params;
  const searchParams = await props.searchParams;

  return <MeetingDetailPage meetingId={params.meetingId} initialMode={searchParams.mode} />;
}
