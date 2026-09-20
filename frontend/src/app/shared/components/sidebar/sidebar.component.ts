import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [CommonModule, RouterLink, RouterLinkActive],
  template: `
    <aside class="fixed top-14 left-0 h-[calc(100vh-3.5rem)] w-[280px] bg-card border-r border-border flex flex-col p-5 gap-6 overflow-y-auto">

      <!-- Mission Control Title -->
      <div>
        <div class="text-xs uppercase tracking-wider text-primary font-semibold">Mission Control</div>
        <div class="text-[10px] text-textLo mt-1">Active Session: <span class="font-mono">0x8A2</span></div>
        <button class="mt-3 w-full py-2 border border-primary text-primary hover:bg-primary/10 rounded-lg text-xs font-semibold transition">
          + New Pipeline
        </button>
      </div>

      <!-- Navigation -->
      <nav class="space-y-1">
        <a routerLink="/story-intake" routerLinkActive="bg-primary/15 text-primary border-l-2 border-primary"
           class="flex items-center gap-3 px-3 py-2 rounded-lg text-xs font-medium text-textLo hover:text-textHi hover:bg-white/5 transition cursor-pointer">
          <span>📊</span><span>Pipeline Stages</span>
        </a>
        <a routerLink="/dashboard" routerLinkActive="bg-primary/15 text-primary border-l-2 border-primary"
           class="flex items-center gap-3 px-3 py-2 rounded-lg text-xs font-medium text-textLo hover:text-textHi hover:bg-white/5 transition cursor-pointer">
          <span>📁</span><span>Workspace Topology</span>
        </a>
        <div class="flex items-center gap-3 px-3 py-2 rounded-lg text-xs font-medium text-textLo hover:text-textHi hover:bg-white/5 transition cursor-pointer">
          <span>💰</span><span>Token Tracker</span>
        </div>
        <div class="flex items-center gap-3 px-3 py-2 rounded-lg text-xs font-medium text-textLo hover:text-textHi hover:bg-white/5 transition cursor-pointer">
          <span>📟</span><span>Terminal Logs</span>
        </div>
        <div class="flex items-center gap-3 px-3 py-2 rounded-lg text-xs font-medium text-textLo hover:text-textHi hover:bg-white/5 transition cursor-pointer">
          <span>&lt;/&gt;</span><span>Code Preview</span>
        </div>
      </nav>
    </aside>
  `
})
export class SidebarComponent {}