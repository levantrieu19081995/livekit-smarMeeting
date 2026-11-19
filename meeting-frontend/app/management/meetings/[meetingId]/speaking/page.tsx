import { MeetingSpeakingPanel } from '@/components/management/speaking/MeetingSpeakingPanel';

export default async function MeetingSpeakingRoute(props: {
  params: Promise<{ meetingId: string }>;
}) {
  const { meetingId } = await props.params;
  return <MeetingSpeakingPanel meetingId={meetingId} attendeeId="history" />;
}
