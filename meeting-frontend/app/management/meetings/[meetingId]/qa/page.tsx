import { MeetingQnaPanel } from '@/components/management/qna/MeetingQnaPanel';

export default async function MeetingQnaRoute(props: { params: Promise<{ meetingId: string }> }) {
  const { meetingId } = await props.params;
  return <MeetingQnaPanel meetingId={meetingId} />;
}
