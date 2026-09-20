import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SidebarComponent } from './shared/components/sidebar/sidebar.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, SidebarComponent],
  template: `
    <app-sidebar></app-sidebar>
    <main class="ml-[280px] min-h-screen p-8 bg-canvas text-textHi">
      <router-outlet></router-outlet>
    </main>
  `
})
export class AppComponent {}