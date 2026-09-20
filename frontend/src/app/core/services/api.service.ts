import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { StoryRequest } from '../models/story-request.model';
import { BudgetStatus } from '../models/pipeline-stage.model';

@Injectable({ providedIn: 'root' })
export class ApiService {

  private readonly BASE = 'http://localhost:8081';

  constructor(private http: HttpClient) {}

  analyzeStory(request: StoryRequest): Observable<{ pipelineId: string }> {
    return this.http.post<{ pipelineId: string }>(
      `${this.BASE}/api/workflows/analyze`, request
    );
  }

  approve(pipelineId: string, checkpointToken: string, approved: boolean): Observable<string> {
    return this.http.post(
      `${this.BASE}/api/workflows/${pipelineId}/approve`,
      { checkpointToken, approved },
      { responseType: 'text' }
    );
  }

  eject(pipelineId: string): Observable<string> {
    return this.http.post(
      `${this.BASE}/api/workflows/${pipelineId}/eject`,
      {},
      { responseType: 'text' }
    );
  }

  getBudgetStatus(): Observable<BudgetStatus> {
    return this.http.get<BudgetStatus>(`${this.BASE}/api/budget/status`);
  }

  getWorkspaceSummary(): Observable<any> {
    return this.http.get<any>(`${this.BASE}/api/workspace/summary`);
  }
}