import { MeetingRoomClient } from './MeetingRoomClient';

export default async function JoinMeetingPage(props: {
  params: Promise<{ meetingId: string }>;
}) {
  const params = await props.params;
  return <MeetingRoomClient meetingId={params.meetingId} />;
}
