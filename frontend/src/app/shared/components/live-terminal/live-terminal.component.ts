import { Component, ElementRef, OnInit, ViewChild, AfterViewChecked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SseService } from '../../../core/services/sse.service';

@Component({
  selector: 'app-live-terminal',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="bg-canvas border border-border rounded-xl overflow-hidden">
      <div class="px-5 py-2.5 border-b border-border flex items-center gap-3">
        <span class="text-[11px] uppercase tracking-wider text-primary font-semibold">📟 Slicer Stream</span>
        <span class="flex items-center gap-1 text-[10px] text-success">
          <span class="w-1.5 h-1.5 rounded-full bg-success animate-pulse"></span> LIVE TAIL
        </span>
      </div>
      <div #terminal class="h-[380px] overflow-y-auto font-mono text-xs p-5 space-y-1.5">
        <div *ngIf="logs.length === 0" class="text-textLo italic">[System] Awaiting pipeline events...</div>
        <div *ngFor="let line of logs" [innerHTML]="line"></div>
      </div>
    </div>
  `
})
export class LiveTerminalComponent implements OnInit, AfterViewChecked {
  @ViewChild('terminal') terminal!: ElementRef<HTMLDivElement>;

  logs: string[] = [];
  private autoScroll = true;

  constructor(private sse: SseService) {}

  ngOnInit(): void {
    this.sse.log$.subscribe((msg) => {
      if (!msg) return;
      const ts = new Date().toLocaleTimeString('en-GB', { hour12: false });
      const level = this.detectLevel(msg);
      const body = this.escapeHtml(msg);

      let colored: string;
      if (level === 'WARN') {
        colored = `<span class="text-warning">${body}</span>`;
      } else if (level === 'ERROR') {
        colored = `<span class="text-danger">${body}</span>`;
      } else if (level === 'EXEC') {
        colored = `<span class="text-primary">${body}</span>`;
      } else if (level === 'SUCCESS') {
        colored = `<span class="text-success">${body}</span>`;
      } else {
        colored = `<span class="text-textHi">${body}</span>`;
      }

      this.logs.push(`<span class="text-textLo">[${ts}]</span> ${colored}`);
      if (this.logs.length > 200) this.logs.shift();
      this.autoScroll = true;
    });
  }

  ngAfterViewChecked(): void {
    if (this.autoScroll && this.terminal) {
      const el = this.terminal.nativeElement;
      el.scrollTop = el.scrollHeight;
    }
  }

  private detectLevel(msg: string): string {
    if (msg.includes('🚨') || msg.includes('❌') || msg.toLowerCase().includes('failed')) return 'ERROR';
    if (msg.includes('⚠️') || msg.toLowerCase().includes('warn') || msg.includes('🔄')) return 'WARN';
    if (msg.includes('✅') || msg.includes('PASS')) return 'SUCCESS';
    if (msg.includes('⚙️') || msg.includes('🏗️') || msg.includes('📋') || msg.includes('🧪') || msg.includes('📨')) return 'EXEC';
    return 'INFO';
  }

  private escapeHtml(s: string): string {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
}