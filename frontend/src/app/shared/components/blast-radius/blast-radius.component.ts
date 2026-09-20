import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SseService } from '../../../core/services/sse.service';

@Component({
  selector: 'app-blast-radius',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div *ngIf="data" class="bg-card border border-border rounded-xl p-5">
      <div class="flex items-center justify-between">
        <div class="text-sm font-semibold text-textHi">📊 Codebase Impact Analysis</div>
        <div class="flex gap-2 text-[10px]">
          <span class="px-2 py-0.5 rounded-md bg-primary/20 text-primary border border-primary/30">
            {{ data.complexity }}
          </span>
          <span class="px-2 py-0.5 rounded-md bg-warning/20 text-warning border border-warning/30">
            {{ data.mode }}
          </span>
        </div>
      </div>

      <div class="mt-4 space-y-2">
        <div *ngFor="let f of data.files" class="flex items-center justify-between text-xs">
          <div class="flex items-center gap-2">
            <span [ngClass]="riskDot(f.downstreamCount)"></span>
            <span class="font-mono text-textHi">{{ f.filePath }}</span>
          </div>
          <div class="flex items-center gap-3 text-textLo">
            <span>{{ (f.confidence * 100).toFixed(0) }}%</span>
            <span class="text-[10px]">{{ f.downstreamCount }} downstream</span>
          </div>
        </div>
      </div>
    </div>
  `
})
export class BlastRadiusComponent implements OnInit {
  data: any = null;

  constructor(private sseService: SseService) {}

  ngOnInit(): void {
    this.sseService.blast$.subscribe(d => {
      if (d) this.data = d;
    });
  }

  riskDot(count: number): string {
    if (count === 0) return 'w-2 h-2 rounded-full bg-success';
    if (count <= 2) return 'w-2 h-2 rounded-full bg-warning';
    return 'w-2 h-2 rounded-full bg-danger';
  }
}