import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'story-intake', pathMatch: 'full' },
  {
    path: 'story-intake',
    loadComponent: () =>
      import('./pages/story-intake/story-intake.component')
        .then(m => m.StoryIntakeComponent)
  },
  {
    path: 'dashboard',
    loadComponent: () =>
      import('./pages/dashboard/dashboard.component')
        .then(m => m.DashboardComponent)
  },
  { path: '**', redirectTo: 'story-intake' }
];