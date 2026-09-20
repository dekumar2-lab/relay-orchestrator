import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { SseService } from '../../core/services/sse.service';
import { StoryRequest } from '../../core/models/story-request.model';

@Component({
  selector: 'app-story-intake',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="max-w-5xl">
      <h1 class="text-2xl font-bold tracking-tight">Story Intake</h1>
      <p class="text-sm text-textLo mt-1">
        Submit a story. The AI will discover affected files, classify complexity, and propose an implementation plan.
      </p>

      <div class="mt-6 grid grid-cols-1 lg:grid-cols-3 gap-5">

        <!-- Left: Details -->
        <div class="bg-card border border-border rounded-xl p-5 space-y-4">
          <div class="text-[10px] font-semibold uppercase tracking-wider text-primary">Details</div>

          <div>
            <label class="text-xs text-textLo">Jira ID</label>
            <input [(ngModel)]="req.jiraId"
                   placeholder="e.g., PROJ-123"
                   class="mt-1 w-full bg-canvas border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"/>
          </div>

          <div>
            <label class="text-xs text-textLo">Repository</label>
            <select [(ngModel)]="req.repository"
                    class="mt-1 w-full bg-canvas border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary">
              <option value="cui">cui</option>
              <option value="payment-service">payment-service</option>
            </select>
          </div>

          <div>
            <label class="text-xs text-textLo">Branch</label>
            <input [(ngModel)]="req.branch"
                   placeholder="main"
                   class="mt-1 w-full bg-canvas border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"/>
          </div>

          <div>
            <label class="text-xs text-textLo">Execution Mode</label>
            <select [(ngModel)]="req.mode"
                    class="mt-1 w-full bg-canvas border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary">
              <option value="FULL_PIPELINE">🚀 Full Pipeline</option>
              <option value="PLAN_ONLY">🏗️ Plan Only</option>
              <option value="CODE_ONLY">⚡ Code Only</option>
              <option value="DIRECT_FIX">🛠️ Direct Fix</option>
            </select>
          </div>
        </div>

        <!-- Right: Story Editor -->
        <div class="lg:col-span-2 bg-card border border-border rounded-xl p-5 flex flex-col">
          <div class="text-[10px] font-semibold uppercase tracking-wider text-primary">Story Editor</div>
          <textarea [(ngModel)]="req.storyDescription"
                    placeholder="Describe the story, requirement, or defect..."
                    class="mt-3 flex-1 min-h-[240px] bg-canvas border border-border rounded-lg px-4 py-3 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-primary"></textarea>

          <div class="mt-4 flex items-center justify-between">
            <span *ngIf="error" class="text-xs text-danger">{{ error }}</span>
            <button (click)="submit()"
                    [disabled]="loading"
                    class="ml-auto px-6 py-2.5 bg-gradient-to-r from-primary to-purple-500 hover:opacity-90 disabled:opacity-50 rounded-lg text-sm font-semibold text-white transition-all">
              {{ loading ? 'Starting...' : '🚀 Analyze Story' }}
            </button>
          </div>
        </div>

      </div>
    </div>
  `
})
export class StoryIntakeComponent {

  loading = false;
  error = '';

  req: StoryRequest = {
    jiraId: '',
    repository: 'cui',
    branch: 'main',
    storyDescription: '',
    mode: 'FULL_PIPELINE'
  };

  constructor(
    private api: ApiService,
    private sse: SseService,
    private router: Router
  ) {}

  submit(): void {
  if (!this.req.storyDescription.trim()) {
    this.error = 'Please enter a story description.';
    return;
  }
  this.loading = true;
  this.error = '';

  this.api.analyzeStory(this.req).subscribe({
    next: (res) => {
      this.router.navigate(['/dashboard'], { queryParams: { pipelineId: res.pipelineId } });
    },
    error: (err) => {
      this.error = 'Failed to start pipeline: ' + (err.message ?? 'Unknown error');
      this.loading = false;
    }
  });
}
}