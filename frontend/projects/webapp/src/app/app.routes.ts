import { Routes } from '@angular/router';

import { pageTitle } from './core/page-titles';
import { keepFragment } from './pages/docs/docs-redirects';

export const routes: Routes = [
  {
    path: '',
    title: pageTitle('/'),
    loadComponent: () => import('./pages/landing/landing.page').then((m) => m.LandingPage),
  },
  {
    path: 'result/:id',
    title: pageTitle('/result/:id'),
    loadComponent: () => import('./pages/result/result.page').then((m) => m.ResultPage),
  },
  {
    path: 'edit/:id',
    title: pageTitle('/edit/:id'),
    loadComponent: () => import('./pages/edit/edit.page').then((m) => m.EditPage),
  },
  {
    path: 'about',
    title: pageTitle('/about'),
    loadComponent: () => import('./pages/about/about.page').then((m) => m.AboutPage),
  },
  {
    path: 'examples',
    title: pageTitle('/examples'),
    loadComponent: () => import('./pages/examples/examples.page').then((m) => m.ExamplesPage),
  },
  {
    path: 'docs',
    loadComponent: () =>
      import('./pages/docs/docs-shell.component').then((m) => m.DocsShellComponent),
    children: [
      {
        path: '',
        title: pageTitle('/docs'),
        loadComponent: () => import('./pages/docs/docs-home.page').then((m) => m.DocsHomePage),
      },
      {
        path: 'fixing',
        title: pageTitle('/docs/fixing'),
        loadComponent: () => import('./pages/docs/fixing.page').then((m) => m.FixingPage),
      },
      {
        path: 'problems',
        title: pageTitle('/docs/problems'),
        loadComponent: () => import('./pages/docs/problems.page').then((m) => m.ProblemsPage),
      },
    ],
  },
  // The old addresses, with their section anchors.
  { path: 'guide', redirectTo: keepFragment('/docs/fixing') },
  { path: 'problems', redirectTo: keepFragment('/docs/problems') },
  {
    path: '**',
    title: pageTitle('/not-found'),
    loadComponent: () => import('./pages/not-found/not-found.page').then((m) => m.NotFoundPage),
  },
];
