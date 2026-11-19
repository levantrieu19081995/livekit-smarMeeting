// Meeting schedules are LocalDateTime values in the application's Vietnam time zone.
export const MEETING_SCHEDULE_TIME_ZONE = 'Asia/Ho_Chi_Minh';

const empty = { date: '', time: '' };

export function getMeetingFormDateTime(value: string | null): { date: string; time: string } {
  if (!value) return empty;
  const local = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})(?::(\d{2})(?:\.\d{1,9})?)?$/.exec(value);
  if (local) {
    const parsed = new Date(`${local[1]}T${local[2]}:00Z`);
    if (
      Number.isNaN(parsed.getTime()) ||
      parsed.toISOString().slice(0, 16) !== `${local[1]}T${local[2]}` ||
      (local[3] && Number(local[3]) > 59)
    )
      return empty;
    // Do not turn a timezone-less business schedule into UTC before editing it.
    return { date: local[1], time: local[2] };
  }
  if (!/(?:Z|[+-]\d{2}:?\d{2})$/i.test(value)) return empty;
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return empty;
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: MEETING_SCHEDULE_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(parsed);
  const part = (name: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === name)?.value ?? '';
  return {
    date: `${part('year')}-${part('month')}-${part('day')}`,
    time: `${part('hour')}:${part('minute')}`,
  };
}
