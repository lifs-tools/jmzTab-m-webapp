import { Routes } from '@angular/router';

import { pageTitle } from './core/page-titles';

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
    path: 'problems',
    title: pageTitle('/problems'),
    loadComponent: () => import('./pages/problems/problems.page').then((m) => m.ProblemsPage),
  },
  {
    path: '**',
    title: pageTitle('/not-found'),
    loadComponent: () => import('./pages/not-found/not-found.page').then((m) => m.NotFoundPage),
  },
];
