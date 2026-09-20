export type PipelineStage =
  | 'CLARIFY'
  | 'ARCHITECT'
  | 'DEVELOP'
  | 'QA'
  | 'HANDOFF'
  | 'COMPLETED'
  | 'FAILED';

export interface SseEvent {
  type: 'log' | 'phase_update' | 'budget_update' | 'checkpoint' | 'complete' | 'error';
  message: string;
  payload?: any;
}

export interface BudgetStatus {
  remaining: number;
  totalLimit: number;
  inputTokens: number;
  outputTokens: number;
}