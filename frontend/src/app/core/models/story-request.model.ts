export interface StoryRequest {
  jiraId: string;
  repository: string;
  branch: string;
  storyDescription: string;
  mode: 'FULL_PIPELINE' | 'PLAN_ONLY' | 'CODE_ONLY' | 'DIRECT_FIX';
}