import { Component } from '@angular/core';
import { Routes } from '@angular/router';

/** Placeholder until Tasks 11–14 provide the pages. */
@Component({ template: '' })
class PendingPage {}

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/landing/landing.page').then((m) => m.LandingPage),
  },
  { path: 'result/:id', component: PendingPage },
  { path: 'about', component: PendingPage },
  { path: 'examples', component: PendingPage },
  { path: '**', component: PendingPage },
];
