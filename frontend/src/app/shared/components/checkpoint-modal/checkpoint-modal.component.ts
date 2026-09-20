import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { SseService } from '../../../core/services/sse.service';
import { ApiService } from '../../../core/services/api.service';

@Component({
  selector: 'app-checkpoint-modal',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div *ngIf="visible" class="fixed inset-0 bg-black/70 backdrop-blur-md flex items-center justify-center z-50">
      <div class="bg-card border border-border rounded-2xl max-w-3xl w-full mx-4 max-h-[80vh] flex flex-col">
        <div class="p-5 border-b border-border">
          <div class="text-sm font-semibold text-textHi">{{ title }}</div>
        </div>
        <div class="p-5 overflow-y-auto flex-1">
          <pre class="bg-canvas p-4 rounded-lg text-xs font-mono text-textHi whitespace-pre-wrap">{{ artifact }}</pre>
        </div>
        <div class="p-5 border-t border-border flex justify-end gap-3">
          <button (click)="approve(true)"
                  [disabled]="submitted"
                  class="px-5 py-2 bg-success hover:bg-green-600 disabled:opacity-50 rounded-lg text-sm font-medium text-white">
            ✅ Approve
          </button>
          <button (click)="approve(false)"
                  [disabled]="submitted"
                  class="px-5 py-2 bg-danger hover:bg-red-600 disabled:opacity-50 rounded-lg text-sm font-medium text-white">
            ❌ Reject
          </button>
        </div>
      </div>
    </div>
  `
})
export class CheckpointModalComponent implements OnInit {
  visible = false;
  submitted = false;
  title = '';
  artifact = '';
  private token = '';
  private pipelineId = '';

constructor(
  private sseService: SseService,
  private api: ApiService,
  private route: ActivatedRoute
) {}

  ngOnInit(): void {
    this.sseService.checkpoint$.subscribe((cp) => {
      if (!cp) return;
      this.title = cp.title ?? 'Checkpoint';
      this.artifact = cp.artifact ?? '';
      this.token = cp.token ?? '';
      this.submitted = false;
      this.visible = true;
    });
  }

approve(approved: boolean): void {
  if (this.submitted) return;
  this.submitted = true;
  const id = this.route.snapshot.queryParamMap.get('pipelineId') || '';
  this.api.approve(id, this.token, approved).subscribe({
    next: () => { this.visible = false; },
    error: () => { this.visible = false; }
  });
}
}