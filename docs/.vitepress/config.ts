import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'NDI Stage Displays',
  description: 'Forge 1.20.1 wiki — LED walls, cameras, kinetics, and real NDI video in Minecraft.',
  lang: 'en-US',
  base: '/',
  cleanUrls: true,
  ignoreDeadLinks: false,
  sitemap: {
    hostname: 'https://wiki.nailec.fr'
  },
  head: [
    [
      'script',
      {
        defer: '',
        src: 'https://seo.streamlineagency.eu/script.js',
        'data-website-id': '33e56770-2f40-4dc4-958f-22405662d2c0'
      }
    ]
  ],
  themeConfig: {
    siteTitle: 'NDI Stage Displays',
    nav: [
      { text: 'Guide', link: '/guide/install' },
      { text: 'Blocks', link: '/blocks/' },
      { text: 'Kinetics', link: '/kinetics/payloads' },
      { text: 'Reference', link: '/reference/recipes' },
      { text: 'GitHub', link: 'https://github.com/nanocodium/ndi-displays' }
    ],
    sidebar: [
      {
        text: 'Guide',
        items: [
          { text: 'Install', link: '/guide/install' },
          { text: 'First wall', link: '/guide/first-wall' },
          { text: 'OBS & Resolume', link: '/guide/ndi-software' },
          { text: 'Video processor', link: '/guide/processor' },
          { text: 'Multiplayer', link: '/guide/multiplayer' },
          { text: 'Troubleshooting', link: '/guide/troubleshooting' }
        ]
      },
      {
        text: 'Blocks',
        items: [
          { text: 'Catalog', link: '/blocks/' },
          { text: 'LED Wall Panel', link: '/blocks/led-panel' },
          { text: 'Blow-Through Panel', link: '/blocks/blow-through-panel' },
          { text: 'LED Floor Tile', link: '/blocks/led-floor' },
          { text: 'Round LED Screen', link: '/blocks/round-screen' },
          { text: 'Curved LED Screen', link: '/blocks/curved-screen' },
          { text: 'Cameras', link: '/blocks/cameras' },
          { text: 'Kinetic LED Winch', link: '/blocks/kinetic-winch' },
          { text: 'Chain Hoist', link: '/blocks/chain-hoist' },
          { text: 'Video Projector', link: '/blocks/projector' },
          { text: 'Winch Park Monitor', link: '/blocks/winch-park-monitor' },
          { text: 'NDI Router', link: '/blocks/ndi-router' },
          { text: 'Multiview Monitor', link: '/blocks/multiview' },
          { text: 'Vision Switcher', link: '/blocks/vision-switcher' },
          { text: 'Pro Monitor', link: '/blocks/pro-monitor' },
          { text: 'Computer', link: '/blocks/computer' },
          { text: 'Equipment Rack', link: '/blocks/equipment-rack' },
          { text: 'Web Terminal', link: '/blocks/web-terminal' }
        ]
      },
      {
        text: 'Items',
        items: [
          { text: 'NDI Configuration Card', link: '/items/ndi-config-card' },
          { text: 'Handheld Camera', link: '/items/handheld-camera' },
          { text: 'Shoulder Camera', link: '/items/shoulder-camera' },
          { text: 'NDI Drone', link: '/items/drone' },
          { text: 'Hoist Remote', link: '/items/hoist-remote' }
        ]
      },
      {
        text: 'Kinetics',
        items: [
          { text: 'Payloads', link: '/kinetics/payloads' },
          { text: 'DMX maps', link: '/kinetics/dmx' }
        ]
      },
      {
        text: 'Reference',
        items: [
          { text: 'Recipes', link: '/reference/recipes' },
          { text: 'Native resolution', link: '/reference/native-resolution' },
          { text: 'Client config', link: '/reference/config' },
          { text: 'Integrations', link: '/reference/integrations' },
          { text: 'Changelog', link: '/reference/changelog' }
        ]
      }
    ],
    search: {
      provider: 'local'
    },
    outline: [2, 3],
    socialLinks: [
      { icon: 'github', link: 'https://github.com/nanocodium/ndi-displays' }
    ],
    footer: {
      message: 'Forge 1.20.1 · NDI SDK v5 / v6 · MIT License',
      copyright: 'NDI® is a registered trademark of Vizrt NDI AB. This project is not affiliated with Vizrt.'
    },
    editLink: {
      pattern: 'https://github.com/nanocodium/ndi-displays/edit/main/docs/:path',
      text: 'Edit this page'
    }
  }
})
