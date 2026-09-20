import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SseService } from '../../../core/services/sse.service';

interface Phase {
  id: string;
  label: string;
  icon: string;
  state: 'queued' | 'active' | 'done';
}

@Component({
  selector: 'app-pipeline-timeline',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="bg-card border border-border rounded-xl p-5">
      <div class="flex items-center justify-between mb-5">
        <div class="text-sm font-semibold text-textHi flex items-center gap-2">
          <span>🔀</span> Active Pipeline: <span class="text-primary">{{ pipelineName }}</span>
        </div>
        <div class="text-[10px] text-textLo uppercase tracking-wider">Live</div>
      </div>

      <div class="flex items-center justify-between gap-3">
        <ng-container *ngFor="let p of phases; let i = index; let last = last">
          <!-- Step -->
          <div class="flex-1 flex flex-col items-center">
            <div [ngClass]="{
                   'w-10 h-10 rounded-lg flex items-center justify-center text-lg transition-all duration-300': true,
                   'bg-primary text-white shadow-[0_0_16px_rgba(139,92,246,0.5)]': p.state === 'active',
                   'bg-success/20 text-success border border-success/40': p.state === 'done',
                   'bg-border/40 text-textLo border border-border': p.state === 'queued'
                 }">
              {{ p.icon }}
            </div>
            <div class="mt-2 text-[11px] font-semibold text-textHi">{{ p.label }}</div>
            <div [ngClass]="{
                   'mt-1 text-[9px] uppercase tracking-wider px-2 py-0.5 rounded': true,
                   'bg-primary/20 text-primary': p.state === 'active',
                   'bg-success/20 text-success': p.state === 'done',
                   'bg-border/40 text-textLo': p.state === 'queued'
                 }">
              {{ p.state === 'queued' ? 'QUEUED' : p.state === 'active' ? 'ACTIVE' : 'DONE' }}
            </div>
          </div>

          <!-- Connector -->
          <div *ngIf="!last" class="flex-1 h-0.5 mt-5 transition-colors duration-300"
               [ngClass]="p.state === 'done' ? 'bg-success' : 'bg-border'"></div>
        </ng-container>
      </div>
    </div>
  `
})
export class PipelineTimelineComponent implements OnInit {

  pipelineName = 'Feature Extraction';

  phases: Phase[] = [
    { id: 'CLARIFY',   label: 'Analysis', icon: '📋', state: 'queued' },
    { id: 'ARCHITECT', label: 'Decision', icon: '🧠', state: 'queued' },
    { id: 'DEVELOP',   label: 'Compile',  icon: '⚙️', state: 'queued' },
    { id: 'QA',        label: 'Test',     icon: '🧪', state: 'queued' }
  ];

  constructor(private sse: SseService) {}

  ngOnInit(): void {
    this.sse.phase$.subscribe((phase) => this.updatePhase(phase));
  }

  private updatePhase(phase: string): void {
    if (!phase) return;

    if (phase === 'COMPLETED' || phase === 'FAILED') {
      this.phases.forEach(p => p.state = 'done');
      return;
    }

    const idx = this.phases.findIndex(p => p.id === phase);
    if (idx === -1) return;

    this.phases.forEach((p, i) => {
      if (i < idx) p.state = 'done';
      else if (i === idx) p.state = 'active';
      else p.state = 'queued';
    });
  }
}