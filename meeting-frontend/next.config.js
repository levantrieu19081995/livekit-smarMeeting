/** @type {import('next').NextConfig} */

const meetingServiceInternalUrl = (
  process.env.MEETING_SERVICE_INTERNAL_URL || 'http://127.0.0.1:8080'
).replace(/\/+$/, '');

const nextConfig = {
  reactStrictMode: false,
  productionBrowserSourceMaps: false,
  distDir: process.env.NEXT_DIST_DIR || '.next',

  images: {
    formats: ['image/webp'],
  },

  webpack: (config) => {
    config.module.rules.push({
      test: /\.mjs$/,
      enforce: 'pre',
      use: ['source-map-loader'],
    });

    return config;
  },

  async rewrites() {
    return [
      {
        source: '/meeting-service/:path*',
        destination: `${meetingServiceInternalUrl}/:path*`,
      },
    ];
  },

  async headers() {
    return [
      {
        source: '/(.*)',
        headers: [
          {
            key: 'Cross-Origin-Opener-Policy',
            value: 'same-origin',
          },
          {
            key: 'Cross-Origin-Embedder-Policy',
            value: 'credentialless',
          },
          {
            key: 'Permissions-Policy',
            value: 'microphone=(self), camera=(self)',
          },
        ],
      },
    ];
  },
};

module.exports = nextConfig;
