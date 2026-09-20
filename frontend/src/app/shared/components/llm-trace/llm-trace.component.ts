import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SseService } from '../../../core/services/sse.service';

@Component({
  selector: 'app-llm-trace',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="bg-card border border-border rounded-xl p-5">
      <div class="flex items-center justify-between mb-4">
        <div class="flex items-center gap-2">
          <span>🤖</span>
          <span class="text-[11px] uppercase tracking-wider text-primary font-semibold">LLM Trace</span>
        </div>
        <span class="text-[10px] font-mono text-textLo">{{ traces.length }} events</span>
      </div>

      <div *ngIf="traces.length === 0" class="text-xs text-textLo italic">
        Awaiting LLM activity...
      </div>

      <div class="space-y-2 max-h-[350px] overflow-y-auto">
        <div *ngFor="let t of traces"
             class="rounded-lg p-3 border"
             [ngClass]="t.phase === 'request' ? 'border-primary/40 bg-primary/5' : 'border-success/40 bg-success/5'">
          <div class="flex items-center justify-between mb-2">
            <span class="text-[10px] uppercase font-semibold"
                  [ngClass]="t.phase === 'request' ? 'text-primary' : 'text-success'">
              {{ t.phase === 'request' ? '🔵 PROMPT SENT' : '🟢 RESPONSE' }}
            </span>
            <span class="text-[10px] font-mono text-textLo">
              <span *ngIf="t.elapsedMs">{{ t.elapsedMs }}ms</span>
              <span *ngIf="t.inputTokens"> · {{ t.inputTokens }} in</span>
              <span *ngIf="t.outputTokens"> · {{ t.outputTokens }} out</span>
            </span>
          </div>

          <pre *ngIf="t.userPrompt"
               class="text-[10px] font-mono text-textHi whitespace-pre-wrap bg-canvas p-2 rounded max-h-[100px] overflow-y-auto">{{ t.userPrompt }}</pre>

          <pre *ngIf="t.content"
               class="text-[10px] font-mono text-textHi whitespace-pre-wrap bg-canvas p-2 rounded max-h-[100px] overflow-y-auto">{{ t.content }}</pre>
        </div>
      </div>
    </div>
  `
})
export class LlmTraceComponent implements OnInit {
  traces: any[] = [];

  constructor(private sse: SseService) {}

  ngOnInit(): void {
    this.sse.llmTrace$.subscribe(t => {
      if (t) {
        this.traces.push(t);
        if (this.traces.length > 20) this.traces.shift();
      }
    });
  }
}