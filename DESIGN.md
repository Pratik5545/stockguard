# StockGuard design direction

## Product and audience

StockGuard manages physical product stock and temporary reservations, not financial securities. Customers browse availability and manage their own holds/orders. Administrators create products and adjust physical inventory. The interface should make quantities, deadlines and consequences easy to scan.

Design read: an inventory workbench for customers and inventory operators, using an orderly stock-ledger visual language. ENERGY 1 / RHYTHM 2 / MOTION 1. Direction authored by the agent under the user's explicit delegation and twenty design preferences.

## Identity and tokens

- Light surfaces support sustained reading of inventory rows and forms; no dark-mode toggle is shipped. A fixed light theme avoids ambiguous status contrast between workspaces.
- Ink `#18332d`, muted text `#52665f`, background `#f3f6f4`, white surfaces, borders `#c6d3cd`.
- Deep green `#165b46` identifies primary actions and selected navigation. Amber `#80520b` is reserved for approaching/expired deadlines. Red `#a12e26` identifies errors and destructive decisions.
- Segoe UI with system fallbacks gives an operational, familiar voice with strong small-text rendering. Body 16px; supporting text 14px; page heading 28–40px. Tabular numerals align prices, quantities and timers. No tiny tracked uppercase headings.
- Spacing scale: 4, 8, 12, 16, 24, 32, 48, 64px. Form controls and buttons have at least 44px targets.
- Radius: 4px statuses, 6px controls, 10px elevated dialogs and grouped forms. Rows use separators rather than floating cards. Only dialogs receive elevation shadows.

## Composition and components

- A dark masthead anchors the product name and session controls. A separate navigation strip maps only to catalog, customer holds/orders and admin inventory.
- Catalog uses horizontal product rows on desktop: identity/description, price, availability, action. Mobile rows stack in that order. This aligns inventory decisions without inventing product imagery.
- Holds emphasize product, deadline and confirm/cancel; terminal records carry quieter status labels. Orders emphasize immutable totals and timestamps.
- Admin uses two distinct task panels for creation and adjustments. Stock results show on-hand/reserved/available together, because these quantities explain the adjustment outcome.
- No statistics banners, fabricated charts, testimonials or analytics. Chart styling is deferred until real time-series endpoints exist; any future chart needs labelled axes, units, period, accessible data and a zero baseline for bars.
- Buttons name their outcome. Inputs have persistent labels, constraints and inline validation. Loading, empty, error, stale/offline and success states contain a next action where needed.
- Status is conveyed with text, not color alone. Focus outlines remain visible; dialogs support Escape; route changes move focus to the page heading. Reduced-motion preferences suppress transitions.
- Responsive navigation wraps into a compact labelled strip; no hidden hover-only controls. Long names, SKUs and IDs wrap. At 320px, rows and forms become single-column.

## Purpose and scope

The repeated ledger divider is the identity motif: it separates actual records, rather than decorating empty space. Modest green highlights focus attention on the next stock action. No external UI library or font download is needed for the existing scope; native HTML supplies forms and modal semantics without another runtime/build dependency.

All amounts use INR, explicitly defined in V1__create_products_and_inventory.sql. Availability is server-sourced and informational. Five-minute countdowns use expiresAt, never a client-invented deadline. No global dashboard totals are inferred from one paginated response.
