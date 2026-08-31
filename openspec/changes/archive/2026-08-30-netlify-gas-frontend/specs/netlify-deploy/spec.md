# Netlify Deploy Specification

## Purpose

Static frontend hosting on Netlify with environment-driven API routing, HTTPS, custom domain support, and SPA redirects for the GPS_CLIENTES Leaflet application.

## Requirements

### Requirement: Static Hosting on Netlify

The system SHALL serve the frontend from Netlify's global CDN with HTTPS enforced.

#### Scenario: Happy path — Netlify production deploy

- GIVEN frontend code pushed to main branch
- WHEN Netlify build completes
- THEN site is accessible at `*.netlify.app` over HTTPS
- AND all static assets (JS, CSS, HTML, images) load without 404

#### Scenario: Custom domain configured

- GIVEN custom domain configured in Netlify DNS
- WHEN user visits custom domain
- THEN site loads over HTTPS with valid TLS certificate
- AND `Content-Security-Policy` header permits required origins

### Requirement: Environment Variables for API Routing

The system SHALL inject `GAS_URL` at build time via Netlify environment variables.

#### Scenario: Happy path — GAS_URL from Netlify env

- GIVEN `GAS_URL` set in Netlify site environment variables
- WHEN frontend builds
- THEN `import.meta.env.GAS_URL` resolves to the configured GAS Web App URL
- AND no localStorage fallback is used

#### Scenario: Edge case — Missing Netlify env var

- GIVEN `GAS_URL` not set in Netlify environment
- WHEN frontend loads
- THEN system SHALL fall back to `localStorage.getItem('GAS_URL')`
- AND if both absent, SHALL default to `https://script.google.com/macros/s/<default>/exec`

### Requirement: SPA Redirects for Client-Side Routing

The system SHALL redirect all non-asset paths to `index.html` for SPA navigation.

#### Scenario: Happy path — Deep link to route

- GIVEN user navigates to `https://app.example.com/rutas/2026-08-30`
- WHEN Netlify receives request
- THEN returns `index.html` with 200 status
- AND frontend router handles `/rutas/2026-08-30`

#### Scenario: Edge case — Static asset request

- GIVEN request for `/app.js` or `/manifest.json`
- WHEN Netlify receives request
- THEN serves actual file (no SPA redirect)
- AND sets appropriate `Cache-Control` header

### Requirement: Security Headers

The system SHALL configure security headers via `netlify.toml`.

#### Scenario: Happy path — Security headers present

- GIVEN any response from Netlify
- WHEN browser receives response
- THEN `X-Frame-Options: DENY` is set
- AND `X-Content-Type-Options: nosniff` is set
- AND `Referrer-Policy: strict-origin-when-cross-origin` is set
- AND `Permissions-Policy` restricts geolocation to self

### Requirement: Build Configuration

The system SHALL build frontend with Vite (or equivalent) outputting to `dist/`.

#### Scenario: Happy path — Build succeeds

- GIVEN `npm run build` executes in Netlify build environment
- WHEN build completes
- THEN `dist/` contains `index.html`, `app.js`, `styles.css`
- AND source maps are generated for debugging

#### Scenario: Edge case — Build failure

- GIVEN TypeScript error or missing dependency
- WHEN build runs
- THEN Netlify reports failure with logs
- AND deploy does not proceed