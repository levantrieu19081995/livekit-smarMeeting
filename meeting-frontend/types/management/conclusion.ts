export interface ConclusionItem {
  content: string;
  sourceRefs: string[];
}

export interface ConclusionTask {
  task: string;
  assignee: string | null;
  deadline: string | null;
  sourceRefs: string[];
}

export interface ConclusionResult {
  summary: string;
  decisions: ConclusionItem[];
  pollConclusions: ConclusionItem[];
  actionItems: ConclusionTask[];
  unresolvedIssues: ConclusionItem[];
}

export interface MeetingConclusion {
  status: 'NOT_GENERATED' | 'GENERATING' | 'GENERATED' | 'FAILED';
  stage: 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | null;
  generationId: string | null;
  sourceMinutesVersion: number | null;
  manualContentBeforeAi: string | null;
  aiGeneratedContent: ConclusionResult | null;
  finalContent: string | null;
  generatedAt: string | null;
  revision: number;
  errorCode: string | null;
  errorMessage: string | null;
  canManage: boolean;
  canGenerate: boolean;
  currentMinutesVersion: number | null;
}

export interface ConclusionSource {
  expectedMinutesVersion: number;
  manualContent: string;
  expectedStoredConclusion: string;
}
