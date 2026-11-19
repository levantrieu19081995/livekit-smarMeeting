import { MeetingCreatePage } from '@/components/meetings/MeetingCreatePage';

export default async function CreateMeetingPage({
  searchParams,
}: {
  searchParams: Promise<{ mode?: string }>;
}) {
  const { mode } = await searchParams;
  return <MeetingCreatePage initialMode={mode} />;
}
