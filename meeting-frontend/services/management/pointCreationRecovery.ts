import { getMeetingFormDateTime } from '@/lib/meetingFormDateTime';
import {
  validateMeetingPointNames,
  validatePointMeetingParticipantRoles,
} from '@/lib/meetingPointRules';
import type {
  ManagementMeetingMutationInput,
  ManagementMeetingParticipant,
  ManagementMeetingRole,
  ManagementMeetingStatus,
} from '@/types/management/meeting';

const allowedFields = new Set([
  'title',
  'description',
  'startTime',
  'endTime',
  'accessType',
  'creationMode',
  'status',
  'pointNames',
  'participants',
]);
const allowedParticipantFields = new Set(['userPublicId', 'invitedEmail', 'fullName', 'role']);
const roles: ManagementMeetingRole[] = ['CHAIRMAN', 'SECRETARY', 'MEMBER'];
const statuses: ManagementMeetingStatus[] = ['UPCOMING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'];

function record(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value);
}

function boundedText(value: unknown, max: number, allowEmpty = false): value is string {
  return typeof value === 'string' && value.length <= max && (allowEmpty || Boolean(value.trim()));
}

export function recoverMeetingCreationInput(
  fingerprint: string,
  expectedMode?: string,
): ManagementMeetingMutationInput | null {
  try {
    if (fingerprint.length > 1_000_000) return null;
    const value: unknown = JSON.parse(fingerprint);
    if (
      !record(value) ||
      Object.keys(value).some((key) => !allowedFields.has(key)) ||
      !['POINT', 'SCHEDULED', 'LATER', 'INSTANT'].includes(String(value.creationMode)) ||
      (expectedMode != null && value.creationMode !== expectedMode) ||
      !['PUBLIC', 'PRIVATE'].includes(String(value.accessType)) ||
      (value.creationMode !== 'SCHEDULED' && value.accessType !== 'PUBLIC')
    )
      return null;
    if (
      !boundedText(
        value.title,
        255,
        value.creationMode === 'INSTANT' || value.creationMode === 'LATER',
      ) ||
      (value.description != null && !boundedText(value.description, 100_000, true))
    )
      return null;
    const scheduled = value.creationMode === 'POINT' || value.creationMode === 'SCHEDULED';
    if (scheduled) {
      if (!boundedText(value.startTime, 40) || !boundedText(value.endTime, 40)) return null;
      if (
        !getMeetingFormDateTime(value.startTime).date ||
        !getMeetingFormDateTime(value.endTime).date ||
        new Date(value.endTime) <= new Date(value.startTime)
      )
        return null;
    }
    if (
      !scheduled &&
      ((value.startTime != null && !boundedText(value.startTime, 40)) ||
        (value.endTime != null && !boundedText(value.endTime, 40)))
    )
      return null;
    if (value.status != null && !statuses.includes(value.status as ManagementMeetingStatus))
      return null;
    if (
      value.creationMode === 'POINT' &&
      (!Array.isArray(value.pointNames) ||
        !value.pointNames.every((name): name is string => typeof name === 'string') ||
        validateMeetingPointNames(value.pointNames))
    )
      return null;
    if (value.creationMode !== 'POINT' && value.pointNames != null) return null;
    if (!Array.isArray(value.participants) || value.participants.length > 500) return null;
    const participants: ManagementMeetingParticipant[] = [];
    const seen = new Set<string>();
    for (const person of value.participants) {
      if (!record(person) || Object.keys(person).some((key) => !allowedParticipantFields.has(key)))
        return null;
      if (
        !boundedText(person.userPublicId, 255) ||
        !boundedText(person.invitedEmail, 255) ||
        !boundedText(person.fullName, 255) ||
        !roles.includes(person.role as ManagementMeetingRole)
      )
        return null;
      if (seen.has(person.userPublicId)) return null;
      seen.add(person.userPublicId);
      participants.push({
        userId: `account-${person.userPublicId}`,
        name: person.fullName,
        email: person.invitedEmail,
        role: person.role as ManagementMeetingRole,
        status: 'INVITED',
      });
    }
    if (validatePointMeetingParticipantRoles(participants)) return null;
    return {
      title: value.title,
      description: typeof value.description === 'string' ? value.description : undefined,
      startTime:
        typeof value.startTime === 'string'
          ? value.startTime
          : value.startTime === null
            ? null
            : undefined,
      endTime:
        typeof value.endTime === 'string'
          ? value.endTime
          : value.endTime === null
            ? null
            : undefined,
      accessType: value.accessType as ManagementMeetingMutationInput['accessType'],
      creationMode: value.creationMode as ManagementMeetingMutationInput['creationMode'],
      ...(value.creationMode === 'POINT' ? { pointNames: value.pointNames as string[] } : {}),
      participants,
      documents: [],
      ...(value.status != null ? { status: value.status as ManagementMeetingStatus } : {}),
    };
  } catch {
    return null;
  }
}

export function recoverPointCreationInput(fingerprint: string) {
  return recoverMeetingCreationInput(fingerprint, 'POINT');
}
