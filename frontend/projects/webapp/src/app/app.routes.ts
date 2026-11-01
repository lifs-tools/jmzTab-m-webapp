import { Routes } from '@angular/router';

import { pageTitle } from './core/page-titles';
import { aboutRedirect, keepFragment } from './pages/docs/docs-redirects';

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
        path: 'versions',
        title: pageTitle('/docs/versions'),
        loadComponent: () => import('./pages/docs/versions.page').then((m) => m.VersionsPage),
      },
      {
        path: 'validating',
        title: pageTitle('/docs/validating'),
        loadComponent: () => import('./pages/docs/validating.page').then((m) => m.ValidatingPage),
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
      {
        path: 'about',
        title: pageTitle('/docs/about'),
        loadComponent: () =>
          import('./pages/docs/service-about.page').then((m) => m.ServiceAboutPage),
      },
    ],
  },
  // The old addresses, with their section anchors.
  { path: 'guide', redirectTo: keepFragment('/docs/fixing') },
  { path: 'problems', redirectTo: keepFragment('/docs/problems') },
  { path: 'about', redirectTo: aboutRedirect },
  {
    path: '**',
    title: pageTitle('/not-found'),
    loadComponent: () => import('./pages/not-found/not-found.page').then((m) => m.NotFoundPage),
  },
];
