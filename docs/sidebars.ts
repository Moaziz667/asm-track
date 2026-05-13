import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docs: [
    {
      type: 'doc',
      id: 'intro',
      label: 'Introduction',
    },
    {
      type: 'category',
      label: 'Architecture',
      collapsed: false,
      items: [
        'architecture/overview',
        'architecture/flows',
        'architecture/auth',
        'architecture/erp-integration',
      ],
    },
    {
      type: 'category',
      label: 'Microservices',
      collapsed: false,
      items: [
        'services/iam-service',
        'services/delivery-service',
        'services/driver-service',
        'services/erp-adapter',
      ],
    },
    {
      type: 'category',
      label: 'Applications',
      collapsed: false,
      items: [
        'apps/admin-app',
        'apps/driver-app',
      ],
    },
    {
      type: 'category',
      label: 'Infrastructure',
      collapsed: true,
      items: [
        'infra/docker',
        'infra/databases',
      ],
    },
  ],
};

export default sidebars;
