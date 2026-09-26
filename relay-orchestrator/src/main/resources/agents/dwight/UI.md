# UI Conventions

The app uses Tailwind CSS + Material Symbols icons + Alpine.js.
There is no build step — all classes are in the HTML.

## Color tokens (never use raw colors)

- Surface: bg-white, bg-slate-50, bg-slate-100
- Border: border-slate-200, border-slate-100
- Text primary: text-slate-900, text-slate-800
- Text muted: text-slate-500, text-slate-400
- Primary action: bg-blue-600 text-white hover:bg-blue-700
- Danger action: bg-red-600 text-white hover:bg-red-700 (confirm)
  OR text-red-600 hover:bg-red-50 (icon button)
- Success state: bg-emerald-50 text-emerald-700 border-emerald-200
- Warning state: bg-amber-50 text-amber-700 border-amber-200

## Component patterns

**Icon buttons** (used for row actions like trash/close):

```html
<button
  class="w-6 h-6 rounded flex items-center justify-center
               text-slate-400 hover:text-red-600 hover:bg-red-50"
>
  <span class="material-symbols-outlined text-[14px]">delete</span>
</button>
```
