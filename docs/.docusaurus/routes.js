import React from 'react';
import ComponentCreator from '@docusaurus/ComponentCreator';

export default [
  {
    path: '/__docusaurus/debug',
    component: ComponentCreator('/__docusaurus/debug', 'cf0'),
    exact: true
  },
  {
    path: '/__docusaurus/debug/config',
    component: ComponentCreator('/__docusaurus/debug/config', '43f'),
    exact: true
  },
  {
    path: '/__docusaurus/debug/content',
    component: ComponentCreator('/__docusaurus/debug/content', 'aa2'),
    exact: true
  },
  {
    path: '/__docusaurus/debug/globalData',
    component: ComponentCreator('/__docusaurus/debug/globalData', 'a55'),
    exact: true
  },
  {
    path: '/__docusaurus/debug/metadata',
    component: ComponentCreator('/__docusaurus/debug/metadata', '121'),
    exact: true
  },
  {
    path: '/__docusaurus/debug/registry',
    component: ComponentCreator('/__docusaurus/debug/registry', '7f2'),
    exact: true
  },
  {
    path: '/__docusaurus/debug/routes',
    component: ComponentCreator('/__docusaurus/debug/routes', '47a'),
    exact: true
  },
  {
    path: '/',
    component: ComponentCreator('/', 'd39'),
    routes: [
      {
        path: '/apps/admin-app',
        component: ComponentCreator('/apps/admin-app', '28c'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/apps/driver-app',
        component: ComponentCreator('/apps/driver-app', '527'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/apps/super-admin-app',
        component: ComponentCreator('/apps/super-admin-app', 'a04'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/architecture/auth',
        component: ComponentCreator('/architecture/auth', 'b34'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/architecture/erp-integration',
        component: ComponentCreator('/architecture/erp-integration', 'eae'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/architecture/flows',
        component: ComponentCreator('/architecture/flows', 'd5e'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/architecture/overview',
        component: ComponentCreator('/architecture/overview', '402'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/infra/databases',
        component: ComponentCreator('/infra/databases', '56c'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/infra/docker',
        component: ComponentCreator('/infra/docker', '1d9'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/intro',
        component: ComponentCreator('/intro', 'f92'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/services/delivery-service',
        component: ComponentCreator('/services/delivery-service', '269'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/services/driver-service',
        component: ComponentCreator('/services/driver-service', 'af9'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/services/erp-adapter',
        component: ComponentCreator('/services/erp-adapter', 'bdd'),
        exact: true,
        sidebar: "docs"
      },
      {
        path: '/services/iam-service',
        component: ComponentCreator('/services/iam-service', '8dd'),
        exact: true,
        sidebar: "docs"
      }
    ]
  },
  {
    path: '*',
    component: ComponentCreator('*'),
  },
];
