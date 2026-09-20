import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { SseEvent } from '../models/pipeline-stage.model';

@Injectable({ providedIn: 'root' })
export class SseService {

  private readonly BASE = 'http://localhost:8081';

  log$         = new BehaviorSubject<string>('');
  phase$       = new BehaviorSubject<string>('');
  budget$      = new BehaviorSubject<any>(null);
  checkpoint$  = new BehaviorSubject<any>(null);
  blast$       = new BehaviorSubject<any>(null);
  complete$    = new BehaviorSubject<boolean>(false);
  llmTrace$ = new BehaviorSubject<any>(null);

  private eventSource?: EventSource;

  connect(pipelineId: string): void {
    this.disconnect();
    this.complete$.next(false);

    const url = `${this.BASE}/api/workflows/${pipelineId}/stream`;
    this.eventSource = new EventSource(url);

    // In connect() — add this listener:
    this.eventSource.addEventListener('llm_trace', (e: MessageEvent) => {
      const evt = JSON.parse(e.data);
      this.llmTrace$.next(evt.payload);
    });

    this.eventSource.addEventListener('log', (e: MessageEvent) => {
      const evt: SseEvent = JSON.parse(e.data);
      this.log$.next(evt.message);
    });

    this.eventSource.addEventListener('phase_update', (e: MessageEvent) => {
      const evt: SseEvent = JSON.parse(e.data);
      this.phase$.next(evt.message);
      if (evt.payload) {
        this.blast$.next(evt.payload);
      }
    });

    this.eventSource.addEventListener('budget_update', (e: MessageEvent) => {
      const evt: SseEvent = JSON.parse(e.data);
      this.budget$.next(evt.payload);
    });

    this.eventSource.addEventListener('checkpoint', (e: MessageEvent) => {
      const evt: SseEvent = JSON.parse(e.data);
      this.checkpoint$.next({
        title: evt.message,
        token: evt.payload?.token,
        artifact: evt.payload?.artifact
      });
    });

    this.eventSource.addEventListener('complete', () => {
      this.complete$.next(true);
      this.disconnect();
    });

    this.eventSource.addEventListener('error', (e: MessageEvent) => {
      try {
        const evt: SseEvent = JSON.parse(e.data);
        this.log$.next('🚨 ' + evt.message);
      } catch {
        this.log$.next('🚨 Stream error');
      }
      this.complete$.next(true);
      this.disconnect();
    });

    this.eventSource.onerror = () => {
      if (!this.complete$.value) {
        setTimeout(() => this.connect(pipelineId), 3000);
      }
    };
  }

  disconnect(): void {
    this.eventSource?.close();
    this.eventSource = undefined;
  }
  
}