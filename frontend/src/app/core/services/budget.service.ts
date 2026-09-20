import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

export interface BudgetState {
  remaining: number;
  used: number;
  inputTokens: number;
  outputTokens: number;
  totalLimit: number;
}

@Injectable({ providedIn: 'root' })
export class BudgetService {

  current$ = new BehaviorSubject<BudgetState>({
    remaining: 1500,
    used: 0,
    inputTokens: 0,
    outputTokens: 0,
    totalLimit: 1500
  });

  update(data: any): void {
    if (!data) return;
    this.current$.next({
      remaining: data.remaining ?? 1500,
      used: 1500 - (data.remaining ?? 1500),
      inputTokens: data.inputTokens ?? 0,
      outputTokens: data.outputTokens ?? 0,
      totalLimit: 1500
    });
  }
}