import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BudgetService, BudgetState } from '../../../core/services/budget.service';

@Component({
  selector: 'app-budget-card',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="bg-card border border-border rounded-xl p-4">
      <div class="text-[10px] font-semibold uppercase tracking-wider text-textLo">Monthly Budget</div>
      <div class="mt-2 flex items-baseline gap-1">
        <span class="text-2xl font-bold text-primary">{{ state.remaining | number:'1.2-2' }}</span>
        <span class="text-xs text-textLo">/ {{ state.totalLimit }} Credits</span>
      </div>
      <div class="mt-3 h-1.5 w-full bg-border rounded-full overflow-hidden">
        <div class="h-full bg-gradient-to-r from-primary to-purple-400 transition-all duration-500"
             [style.width.%]="percentRemaining"></div>
      </div>
      <div class="mt-2 flex justify-between text-[10px] text-textLo font-mono">
        <span>IN {{ state.inputTokens }}</span>
        <span>OUT {{ state.outputTokens }}</span>
      </div>
      <div class="mt-3 flex gap-2">
        <span class="px-2 py-0.5 text-[10px] rounded-md bg-primary/20 text-primary border border-primary/30">Caveman 65%</span>
        <span class="px-2 py-0.5 text-[10px] rounded-md bg-primary/20 text-primary border border-primary/30">Ponytail 54%</span>
      </div>
    </div>
  `
})
export class BudgetCardComponent implements OnInit {
  state: BudgetState = {
    remaining: 1500,
    used: 0,
    inputTokens: 0,
    outputTokens: 0,
    totalLimit: 1500
  };

  get percentRemaining(): number {
    return Math.max(0, Math.min(100, (this.state.remaining / this.state.totalLimit) * 100));
  }

  constructor(private budgetService: BudgetService) {}

  ngOnInit(): void {
    this.budgetService.current$.subscribe((s) => this.state = s);
  }
}