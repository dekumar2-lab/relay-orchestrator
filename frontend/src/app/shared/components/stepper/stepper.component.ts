import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SseService } from '../../../core/services/sse.service';

interface Step {
  id: string;
  label: string;
  icon: string;
  state: 'pending' | 'active' | 'done';
}

@Component({
  selector: 'app-stepper',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="space-y-1">
      <div *ngFor="let step of steps; let i = index" class="flex items-start gap-3">
        <!-- Dot + connector -->
        <div class="flex flex-col items-center">
          <div [ngClass]="{
                 'w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold transition-all duration-300': true,
                 'bg-primary text-white shadow-[0_0_12px_rgba(139,92,246,0.8)]': step.state === 'active',
                 'bg-success text-white': step.state === 'done',
                 'bg-border text-textLo': step.state === 'pending'
               }">
            <span *ngIf="step.state === 'done'">✓</span>
            <span *ngIf="step.state !== 'done'">{{ i + 1 }}</span>
          </div>
          <div *ngIf="i < steps.length - 1"
               [ngClass]="{
                 'w-0.5 h-6 transition-colors duration-300': true,
                 'bg-success': step.state === 'done',
                 'bg-border': step.state !== 'done'
               }"></div>
        </div>
        <!-- Label -->
        <div class="pt-0.5 text-sm transition-opacity duration-300"
             [ngClass]="{
               'text-primary font-semibold': step.state === 'active',
               'text-textHi': step.state === 'done',
               'text-textLo opacity-50': step.state === 'pending'
             }">
          {{ step.icon }} {{ step.label }}
        </div>
      </div>
    </div>
  `
})
export class StepperComponent implements OnInit {

  steps: Step[] = [
    { id: 'CLARIFY',   label: 'Clarifier',   icon: '📋', state: 'pending' },
    { id: 'ARCHITECT', label: 'Architect',   icon: '🏗️', state: 'pending' },
    { id: 'DEVELOP',   label: 'Developer',   icon: '⚙️', state: 'pending' },
    { id: 'QA',        label: 'QA Enforcer', icon: '🧪', state: 'pending' },
    { id: 'HANDOFF',   label: 'Handoff',     icon: '📨', state: 'pending' }
  ];

  constructor(private sseService: SseService) {}

  ngOnInit(): void {
    this.sseService.phase$.subscribe((phase) => this.updatePhase(phase));
  }

  private updatePhase(phase: string): void {
    if (!phase) return;

    if (phase === 'COMPLETED' || phase === 'FAILED') {
      this.steps.forEach(s => s.state = 'done');
      return;
    }

    const idx = this.steps.findIndex(s => s.id === phase);
    if (idx === -1) return;

    this.steps.forEach((s, i) => {
      if (i < idx) s.state = 'done';
      else if (i === idx) s.state = 'active';
      else s.state = 'pending';
    });
  }
}