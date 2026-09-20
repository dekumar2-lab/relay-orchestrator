import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { PipelineTimelineComponent } from '../../shared/components/pipeline-timeline/pipeline-timeline.component';
import { LiveTerminalComponent } from '../../shared/components/live-terminal/live-terminal.component';
import { CheckpointModalComponent } from '../../shared/components/checkpoint-modal/checkpoint-modal.component';
import { SseService } from '../../core/services/sse.service';
import { BudgetService } from '../../core/services/budget.service';
import { ApiService } from '../../core/services/api.service';
import { LlmTraceComponent } from '../../shared/components/llm-trace/llm-trace.component';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, PipelineTimelineComponent, LiveTerminalComponent, CheckpointModalComponent],
  template: `
    <div class="space-y-5">

      <!-- Horizontal Pipeline Timeline -->
      <app-pipeline-timeline></app-pipeline-timeline>

      <!-- Two-Column: Token Optimization + Workspace Topology -->
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <div class="bg-card border border-border rounded-xl p-5">
          <div class="flex items-center gap-2 mb-4">
            <span>⚡</span>
            <span class="text-[11px] uppercase tracking-wider text-textLo font-semibold">Token Optimization</span>
          </div>
          <div class="space-y-4">
            <div class="flex items-center justify-between">
              <div>
                <div class="text-[10px] uppercase tracking-wider text-textLo">Caveman Slicer</div>
                <div class="text-2xl font-bold text-primary mt-1">65%</div>
                <div class="text-[10px] text-textLo mt-1">Saved ~12.4k tkn</div>
              </div>
              <div class="text-3xl">🦴</div>
            </div>
            <div class="border-t border-border pt-3">
              <div class="text-[10px] uppercase tracking-wider text-textLo">Ponytail Minimizer</div>
              <div class="text-2xl font-bold text-warning mt-1">54%</div>
              <div class="text-[10px] text-textLo mt-1">Reduced ~8.1k tkn</div>
            </div>
          </div>
        </div>

        <div class="bg-card border border-border rounded-xl p-5">
          <div class="flex items-center justify-between mb-4">
            <div class="flex items-center gap-2">
              <span>📁</span>
              <span class="text-[11px] uppercase tracking-wider text-textLo font-semibold">Workspace Topology</span>
            </div>
            <span class="text-[10px] font-mono text-textLo">graphify-report.json</span>
          </div>
          <div class="font-mono text-xs space-y-1.5">
            <div class="flex items-center gap-2 text-textHi"><span>📦</span><span>root</span></div>
            <div class="flex items-center gap-2 ml-4 text-cyan-400"><span>📁</span><span>src</span></div>
            <div class="flex items-center gap-2 ml-8 text-success"><span>📄</span><span>PaymentService.java</span></div>
            <div class="flex items-center gap-2 ml-8 text-success"><span>📄</span><span>PaymentClient.java</span></div>
            <div class="flex items-center gap-2 ml-4 text-cyan-400"><span>📁</span><span>config</span></div>
            <div class="flex items-center gap-2 ml-8 text-warning"><span>📄</span><span>application.yml</span></div>
          </div>
        </div>
      </div>

      <!-- Live Terminal -->
      <app-live-terminal></app-live-terminal>

      <!-- Post Completion -->
      <div *ngIf="completed" class="bg-card border border-border rounded-xl p-5 flex items-center justify-between">
        <div>
          <div class="text-sm font-semibold text-success">✅ Pipeline Complete</div>
          <div class="text-xs text-textLo mt-1">All phases finished. Review the artifacts below.</div>
        </div>
        <div class="flex gap-3">
          <button class="px-4 py-2 border border-primary text-primary hover:bg-primary/10 rounded-lg text-xs font-medium">📄 Design Doc</button>
          <button class="px-4 py-2 border border-primary text-primary hover:bg-primary/10 rounded-lg text-xs font-medium">📊 Final Report</button>
        </div>
      </div>

      <!-- Checkpoint Modal -->
      <app-checkpoint-modal></app-checkpoint-modal>
    </div>
    <app-llm-trace></app-llm-trace>
  `
})
export class DashboardComponent implements OnInit, OnDestroy {

  completed = false;
  pipelineId = '';

  constructor(
    private sse: SseService,
    private budget: BudgetService,
    private api: ApiService,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    // Read pipelineId from query params
    this.pipelineId = this.route.snapshot.queryParamMap.get('pipelineId') || '';

    // Wire subscriptions
    this.sse.budget$.subscribe(b => this.budget.update(b));
    this.sse.complete$.subscribe(c => { if (c) this.completed = true; });

    // Connect SSE
    if (this.pipelineId) {
      this.sse.connect(this.pipelineId);
    } else {
      console.warn('No pipelineId in URL — cannot connect SSE');
    }
  }

  ngOnDestroy(): void {
    this.sse.disconnect();
  }
}