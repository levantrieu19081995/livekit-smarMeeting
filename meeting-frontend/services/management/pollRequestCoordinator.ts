// Preserve the Poll API while sharing its unchanged request coordination.
export {
  ManagementRequestCoordinator as PollRequestCoordinator,
  withManagementDeadline as withPollDeadline,
} from './managementRequestCoordinator';
