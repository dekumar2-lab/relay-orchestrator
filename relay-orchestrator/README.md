## What Changed vs The Old README

| Old                               | New                                                               |
| --------------------------------- | ----------------------------------------------------------------- |
| Assumed Phase 1 only              | Full status through Phase 1.3                                     |
| No architecture section           | Package tree + three key rules                                    |
| No phases table                   | Complete phase-by-phase breakdown                                 |
| No token reduction section        | Six mechanisms, savings per mechanism, how caching actually works |
| No log-reading guide              | Two channels, prefix reference, normal run pattern                |
| No "how to tell cache is working" | Three places to look, what to expect                              |
| No DB documentation               | Table list, sample queries, common patterns                       |
| No config schema                  | Full YAML block                                                   |
| No verification tests             | Five tests you can run right now                                  |
| No roadmap table                  | Full Phase 2–6 forecast                                           |
| Old "honest placeholders" section | Now just a roadmap — those pages are mostly real                  |

## What To Do Now

1. Paste the new README.md over the existing one.
2. Commit:

```powershell
git add README.md
git commit -m "docs: update README with phases, token strategy, log guide, DB details"
```
