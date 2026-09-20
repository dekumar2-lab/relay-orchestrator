export interface FileCandidate {
  filePath: string;
  confidence: number;
  reason: string;
  downstreamCount: number;
  language: string;
}