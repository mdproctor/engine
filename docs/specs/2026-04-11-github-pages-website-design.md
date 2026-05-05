# CaseHub GitHub Pages Website — Design Spec

**Date:** 2026-04-11  
**Status:** Approved  
**URL:** `https://mdproctor.github.io/casehub`

---

## Overview

A GitHub Pages site for the CaseHub project, hosted at `mdproctor.github.io/casehub`. The site serves three purposes: a landing page explaining the framework, a blog publishing the development diary, and a docs section (placeholder for now). Built with full Jekyll in `docs/`, following the Sparge project pattern exactly (`/Users/mdproctor/claude/sparge`).

---

## Structure

Jekyll lives entirely in `docs/`. The GitHub Actions workflow builds from `docs/` and deploys to GitHub Pages. No hybrid static/Jekyll approach — everything goes through Jekyll.

```
casehub/
└── docs/
    ├── _config.yml
    ├── Gemfile
    ├── index.html                   # layout: landing (frontmatter only)
    ├── _layouts/
    │   ├── default.html             # nav + footer — single source of truth
    │   ├── landing.html             # hero + stats + features + blog preview
    │   ├── post.html                # sidebar (recent posts) + article
    │   └── doc.html                 # placeholder layout
    ├── _posts/                      # blog posts (moved from docs/blog/)
    │   ├── 2026-03-27-mdp01-wanted-sketch-got-framework.md
    │   ├── 2026-03-28-mdp02-architecture-behind-casehub.md
    │   ├── 2026-04-09-mdp03-pojo-graph-and-goals.md
    │   └── 2026-04-09-mdp04-two-casehubs-one-design.md
    ├── blog/
    │   └── index.html               # blog listing page
    ├── docs-site/
    │   └── index.md                 # "Docs coming soon" placeholder
    └── assets/
        └── css/
            └── main.css             # SC2 Muted Teal custom theme
```

**Note:** The existing `docs/blog/*.md` post files move to `docs/_posts/`. The `docs/blog/mockups/` subdirectory is not moved — it is excluded from the Jekyll build via `_config.yml`. CLAUDE.md is updated to reflect the new location for future blog post authoring.

---

## Jekyll Configuration

```yaml
title: CaseHub
description: "Collaborative AI problem-solving, built for Quarkus."
baseurl: "/casehub"
url: "https://mdproctor.github.io"
author: Mark Proctor

permalink: pretty

markdown: kramdown
kramdown:
  input: GFM

plugins:
  - jekyll-feed

exclude:
  - superpowers/
  - adr/
  - research/
  - summaries/
  - design-snapshots/
  - blog/mockups/
  - DESIGN.md
  - retro-issues.md
  - Gemfile
  - Gemfile.lock

defaults:
  - scope:
      path: ""
      type: "posts"
    values:
      layout: post
      permalink: /blog/:year/:month/:day/:title/
  - scope:
      path: "docs-site"
      type: "pages"
    values:
      layout: doc
```

---

## Color Scheme

SC2 Muted Teal palette — dark, readable, unmistakably StarCraft II without neon glow:

| Variable | Value | Usage |
|----------|-------|-------|
| `--bg-deep` | `#080d12` | Page background |
| `--bg-card` | `#0e1820` | Cards, nav, footer |
| `--border` | `#1a2e38` | Borders, dividers |
| `--accent` | `#2aa8c4` | SC2 teal — links, CTAs, active states |
| `--text` | `#b8d8e0` | Body text |
| `--text-muted` | `#4a7a8a` | Dates, metadata, secondary text |

No glow effects. No neon. Accent used sparingly on borders and labels.

---

## Landing Page

Layout: `landing.html` wrapping `default.html`. Sections top to bottom:

1. **Hero** — dark background with subtle SC2 grid pattern. Eyebrow: "Open Source Framework". Headline: "Collaborative AI problem-solving, built for Quarkus". Byline: "Blending Blackboard Architecture with CMMN, where orchestration meets choreography for Agentic AI." Two CTAs: "View on GitHub ↗" (primary/teal) and "Read the blog →" (ghost).

2. **Stats strip** — four stats: Java 21 / Quarkus 3.17 / 5 Modules / CMMN Semantics.

3. **Features** — three rows alternating text + code snippet box:
   - CaseFile shared workspace (entry/produces contract, CaseEngine loop)
   - Dual execution model (orchestrated vs autonomous/choreographed)
   - Resilience built in (RetryPolicy, TimeoutEnforcer, DLQ config)

4. **Blog preview** — three most recent posts as cards. "Read all posts →" link.

5. **Footer** — logo, nav links (Docs, Blog, GitHub), Apache 2.0 licence note.

---

## Blog Posts

All four posts move from `docs/blog/` to `docs/_posts/` and gain Jekyll frontmatter:

```yaml
---
layout: post
title: "Wanted a Sketch, Got a Framework"
date: 2026-03-27
tags: [day-zero, architecture]
excerpt: "One session, 73 files, 14,003 lines..."
---
```

The `post.html` layout provides a sidebar with navigation and a list of recent posts, and an article area with date, title, tags, and content.

---

## Blog Index

`docs/blog/index.html` — a simple listing of all posts, grouped or listed by date. Same layout as rest of site (via `default.html`). No pagination needed at 4 posts.

---

## Docs Placeholder

`docs/docs-site/index.md` — single page with frontmatter `layout: doc`, title "Documentation", and body text: "Documentation is coming soon. In the meantime, see [DESIGN.md on GitHub](https://github.com/mdproctor/casehub/blob/main/docs/DESIGN.md) for the full architecture specification."

---

## GitHub Actions

Identical to Sparge's `pages.yml`, with `working-directory: docs`:

```yaml
name: Deploy site to GitHub Pages
on:
  push:
    branches: ["main"]
  workflow_dispatch:
permissions:
  contents: read
  pages: write
  id-token: write
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: ruby/setup-ruby@v1
        with:
          ruby-version: '3.3'
          bundler-cache: true
          working-directory: docs
      - uses: actions/configure-pages@v5
        id: pages
      - name: Build with Jekyll
        run: bundle exec jekyll build --baseurl "${{ steps.pages.outputs.base_path }}"
        working-directory: docs
        env:
          JEKYLL_ENV: production
      - uses: actions/upload-pages-artifact@v3
        with:
          path: docs/_site
  deploy:
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    runs-on: ubuntu-latest
    needs: build
    steps:
      - uses: actions/deploy-pages@v4
        id: deployment
```

GitHub Pages source must be set to **GitHub Actions** in repo Settings → Pages.

---

## CLAUDE.md Updates

- Blog posts are authored in `docs/_posts/` (not `docs/blog/`)
- Jekyll site lives in `docs/` — do not add non-Jekyll content there without adding to `exclude` in `_config.yml`
- Project type remains `type: java` — the site is a sub-concern, not a blog project

---

## Out of Scope

- Docs content (placeholder only — full docs are a separate effort)
- Search functionality
- Dark/light mode toggle
- Comments or RSS promotion
- Custom domain
