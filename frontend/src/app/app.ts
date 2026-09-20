import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SidebarComponent } from './shared/components/sidebar/sidebar.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, SidebarComponent],
  template: `
    <div class="min-h-screen bg-canvas text-textHi">
      <!-- Top Header -->
      <header class="h-14 px-6 flex items-center justify-between bg-card border-b border-border">
        <div class="flex items-center gap-4">
          <span class="text-base font-bold tracking-tight text-textHi">Relay Race Orchestrator</span>
          <span class="flex items-center gap-1.5 text-[11px]">
            <span class="w-2 h-2 rounded-full bg-success animate-pulse"></span>
            <span class="text-textLo uppercase tracking-wider">Status: Healthy</span>
          </span>
          <span class="text-[11px] text-textLo">Model: GPT-4o-mini</span>
        </div>
        <div class="flex items-center gap-4">
          <div class="text-[11px] text-textLo">
            Token Budget: <span class="text-primary font-semibold">1,500</span>
          </div>
          <button class="px-4 py-1.5 bg-primary hover:bg-purple-600 rounded-lg text-xs font-semibold text-white transition">
            Run Orchestration
          </button>
        </div>
      </header>

      <!-- Layout -->
      <div class="flex">
        <app-sidebar></app-sidebar>
        <main class="ml-[280px] flex-1 p-6">
          <router-outlet></router-outlet>
        </main>
      </div>
    </div>
  `
})
export class App {}